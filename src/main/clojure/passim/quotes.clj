(ns passim.quotes
  (:require [clojure.string :as s]
            [clojure.java.io :as jio]
            [clojure.data.json :as json]
            [ciir.utils :refer :all]
            [passim.utils :refer :all]
            [passim.galago :refer :all])
  (:import (passim.utils Alignment)
           (edu.berkeley.nlp.lm.io LmReaders)
           (edu.berkeley.nlp.lm.cache ArrayEncodedCachingLmWrapper)
           (org.lemurproject.galago.core.index IndexPartReader KeyIterator)
           (org.lemurproject.galago.core.index.disk DiskIndex)
           (org.lemurproject.galago.core.parse Document)
           (org.lemurproject.galago.core.retrieval Retrieval RetrievalFactory)
           (org.lemurproject.galago.tupleflow Parameters Utility)))

(defn- index-positions
  "Returns map of terms to their positions in sequence"
  [s]
  (->> s
       (map-indexed vector)
       (reduce
        (fn [map [pos word]]
          (merge-with (comp vec concat) map {word [pos]}))
        {})
       (into {})))

(defn- index-tokens
  [docs gram]
  (let [names (mapv first docs)
        texts (map second docs)
        tok-docs (map galago-tokenize texts)
        doc-offsets (reductions + 0 (map (comp inc count) texts))
        toks (map #(.terms %) tok-docs)
        idx (apply concat (map-indexed (fn [pos words] (map #(vector % pos) words)) toks))
        words (mapv first idx)]
    {:names names
     :positions (mapv second idx)
     :text (s/join "\n" texts)
     :starts (vec (mapcat
                   (fn [toks off] (mapv #(+ % off) (.termCharBegin toks)))
                   tok-docs
                   doc-offsets))
     :stops (vec (mapcat
                  (fn [toks off] (mapv #(+ % off) (.termCharEnd toks)))
                  tok-docs
                  doc-offsets))
     :words words
     :terms (->> words
                 (partition gram 1)
                 (map #(s/join "~" %))
                 vec)}))

(defn- term-hits
  [^KeyIterator ki max-count bad-docs terms]
  (.reset ki)
  (reduce
   (fn [m term]
     (.skipToKey ki (Utility/fromString term))
     (if (= (.getKeyString ki) term)
       (let [vi (.getValueIterator ki)]
         (if (<= (.totalEntries vi) max-count)
           (assoc m term (vec (remove #(bad-docs (first %)) (value-iterator-seq vi))))
           m))
       m))
   {}
   (sort terms)))

(defn load-tsv
  [fname]
  (map #(s/split % #"\t") (s/split (slurp fname) #"\n")))

(defn lm-intern
  [lm words]
  (let [windex (.getWordIndexer lm)]
    (int-array (map #(.getIndexPossiblyUnk windex %) words))))

(defn lm-score
  [lm words]
  (let [order (.getLmOrder lm)
        wids (lm-intern lm words)]
    (+
     (reduce + (map #(float (.getLogProb lm wids 0 %))
                    (range 1 (inc (min order (count wids))))))
     (reduce + (map #(float (.getLogProb lm wids % (+ % order)))
                    (range 1 (inc (- (count wids) order))))))))

     ;; (loop [lp (float 0)
     ;;        i 1]
     ;;   (if (<= i (min order (count wids)))
     ;;     (recur (+ lp (float (.getLogProb lm wids 0 i)))
     ;;            (inc i))
     ;;     lp))
     ;; (loop [lp (float 0)
     ;;        i 1]
     ;;   (if (<= i (- (count wids) order))
     ;;     (recur (+ lp (float (.getLogProb lm wids i (+ i order))))
     ;;            (inc i))
     ;;     lp)))))

(defn lm-stats
  [lm s1 s2]
  (let [core1
        (-> s1
            (s/replace-first #"^\S*\s+" "")
            (s/replace-first #"\s+\S*$" "")
            (s/replace #"-" ""))
        w1 (s/split core1 #" ")]
    {:lm1 (lm-score lm w1)
     :lm1words (count w1)
     :lm1chars (count s1)}))

(defn- extract-bbox
  [coords]
  (let [x (->> coords (map #(Integer/parseInt (nth % 1))) (reduce min))
        y (->> coords (map #(Integer/parseInt (nth % 4))) (reduce min))
        w (- (->> coords (map #(Integer/parseInt (nth % 3))) (reduce max)) x)
        h (- (->> coords (map #(Integer/parseInt (nth % 2))) (reduce max)) y)]
    [x y w h]))

(defn- make-region-url
  [id n bbox]
  (let [pageref (if (re-find #"^[0-9]+$" n) (dec (Integer/parseInt n)) n)]
    (str "http://www.archive.org/download/" id "/page/leaf" pageref
         (apply format "_x%d_y%d_w%d_h%d.jpg" bbox))))

(defn- zap-tags
  [x]
  (-> x
      (s/replace #"<lb>" "\n")
      (s/replace #"</?[A-Za-z][^>]*>" "")))

(defn- doc-passage
  [^Document d start end]
  (let [[series n] (doc-id-parts (.name d))
        wends (.termCharEnd d)
        len (count (.terms d))
        context 10
        soff (if (> start 0)
               (.get wends (dec start))
               0)
        bpref (if (> start 0)
                (.get wends (max 0 (dec (- start context))))
                0)
        eoff (.get wends (dec end))
        esuff (.get wends (dec (min len (+ end context))))
        raw (subs (.text d) soff eoff)
        m (doc-meta d)
        base-url (m "url")
        info {:series series
              :n n
              :text2 (zap-tags raw)
              :prefix2 (zap-tags (subs (.text d) bpref soff))
              :suffix2 (zap-tags (subs (.text d) eoff esuff))
              }
        clip
        {:url
         (if-let [coords (re-seq #" coords=\"([0-9]+),([0-9]+),([0-9]+),([0-9]+)" raw)]
           (make-region-url series n (extract-bbox coords))
           (if (re-find #"<w p=" raw)
             (loc-url base-url raw)
             base-url))}]
    (merge info clip)))

(defn- proc-aligned-doc
  [out1 out2 idx sword1 eword1 ^Document doc sword2 eword2]
  (let [[id n] (doc-id-parts (.name doc))
        w1 (:words idx)
        raw (.text doc)
        terms (.terms doc)
        wends (.termCharEnd doc)]
    ;;(println (s/join " " (subvec w1 sword1 eword1)))
    (loop [res []
           c1 (seq (s/trimr out1))
           c2 (seq (s/trimr out2))
           s1 sword1
           s2 sword2]
      (if (and (not-empty c1) (not-empty c2))
        (let [f1 (first c1)
              f2 (first c2)]
          (recur
           ;; Maybe we should record words even when just the
           ;; canonical text has a word break. This would
           ;; allow... There is a bug below, which I think ultimately
           ;; comes from the book indexing code: galago tokenizes too
           ;; much.
           (if (and (= \space f1) (= \space f2))
             (let [soff (if (> s2 0)
                          (+ 5 (.get wends (dec s2)))
                          0)
                   eoff (+ 4 (.get wends s2))
                   raw (subs (.text doc) soff eoff)
                   coords (re-seq #" coords=\"([0-9]+),([0-9]+),([0-9]+),([0-9]+)" raw)]
               (conj res
                     (merge
                      {:id id
                       :p n
                       :s1 s1
                       :s2 s2
                       :w1 (w1 s1)
                       :w2 (.get terms s2)
                       :cite (-> s1 ((:positions idx)) ((:names idx)))}
                      (when (seq coords)
                        (let [bbox (extract-bbox coords)]
                          {:bbox bbox
                           :url (make-region-url id n bbox)})))))
             res)
           (rest c1)
           (rest c2)
           (if (= \space f1) (inc s1) s1)
           (if (= \space f2) (inc s2) s2)))
        res))))

(defn- get-page-hits
  [idx ki ri max-count max-gap bad-docs]
  (let [term-pos (index-positions (:terms idx))]
    (->> term-pos
         keys
         (term-hits ki max-count bad-docs)
         (reduce
          (fn [m [t d]]
            (let [tf (count d)
                  pos (mapv #(vector % tf) (term-pos t))]
              (merge-with
               (comp vec concat) m
               (into {} (map #(vector (first %) (mapv (fn [x] (conj x (nth % 2))) pos)) d)))))
          {})
         (map
          (fn [[k v]]
            [(.getDocumentName ri (int k)) (vec (sort v))]))
         (map
          (fn [[page thits]]
            (let [matches
                  (map #(mapv first %)
                       (partition-when
                        (fn [[[s1 _ s2] [e1 _ e2]]]
                          (or
                           (> (- e1 s1) max-gap)
                           (and (not= e1 -1)
                                (or
                                 (> (- (reduce min e2) (reduce max s2)) (* max-gap 2))
                                 (> (- (reduce min s2) (reduce max e2)) (* max-gap 2))))))
                        (partition 2 1 [[-1 0 []]] thits)))]
              [page
               (mapv (fn [span]
                       (let [pos (mapv first span)
                             phits (mapcat #(nth % 2) span)
                             s1 (first pos)
                             e1 (peek pos)
                             raw-s2 (reduce min phits)
                             raw-e2 (reduce max phits)
                             raw-len2 (- raw-e2 raw-s2)
                             ;; Terrible HACK: fall back to unique n-gram features
                             uniq-phits (->> span (map #(nth % 2)) (filter #(= 1 (count %))) (mapv first))
                             [s2 e2]
                             (if (and (> raw-len2 max-gap)
                                      (> (/ (inc raw-len2) (inc (- e1 s1))) 10)
                                      (> (/ (count uniq-phits) (count phits)) 0.7))
                               [(reduce min uniq-phits) (reduce max uniq-phits)]
                               [raw-s2 raw-e2])
                             ]
                         ;;(->> % (map second) count)
                         ;; I see: the problem is that we score
                         ;;  only the overlap but we'd like to
                         ;;  score the likelihood of the whole
                         ;;  reference passage.  Adjust this score
                         ;;  by number of high-freq terms?
                         ;; 0 ;; (* (- (- end start) (count pos)) (Math/log (inc (/ 1 max-count))))
                         [(->> span (map second) (map #(Math/log1p (/ 1 %))) (reduce +))
                          s1 e1 s2 e2
                          span
                          ]))
                     matches)]))))))

(defn- proc-page
  [idx ri lm min-score words [page spans]]
  (if-let [good-spans (seq (filter #(>= (first %) min-score) spans))]
    (let [doc-data (get-index-doc ri page)
          term-count (count (:words idx))
          pterms (vec (.terms doc-data))
          n2 (count pterms)
          m (doc-meta doc-data)
          title (m "title")
          date (m "date")
          language (m "language")]
      (map (fn [[score s e min2 max2]]
             (merge
              {:date date
               :title title
               :language language
               :score score
               :page page}
              (try
                (let [s1 (max 0 (- s 50))
                      c1 (join-alnum-tokens
                          (subvec (:words idx)
                                  s1
                                  (min term-count (+ e 50))))
                      s2 (max 0 (- min2 50))
                      e2 (min n2 (+ max2 50))
                      c2 (join-alnum-tokens (subvec pterms s2 e2))
                      alg (jaligner.SmithWatermanGotoh/align
                           (jaligner.Sequence. c1)
                           (jaligner.Sequence. c2)
                           match-matrix 5 0.5)
                      out1 (String. (.getSequence1 alg))
                      out2 (String. (.getSequence2 alg))
                      os1 (.getStart1 alg)
                      os2 (.getStart2 alg)
                      sword1 (+ s1 (space-count (subs c1 0 os1))
                                (if (spacel? out1) 1 0))
                      sword2 (+ s2 (space-count (subs c2 0 os2))
                                (if (spacel? out2) 1 0))
                      eword1 (+ sword1 1 (space-count (s/trim out1)))
                      eword2 (+ sword2 1 (space-count (s/trim out2)))
                      start ((:starts idx) sword1)
                      stop ((:stops idx) (dec eword1))]
                  (merge
                   (doc-passage doc-data sword2 eword2)
                   (alignment-stats (Alignment. out1 out2 sword1 sword2 eword1 eword2))
                   (when lm (lm-stats lm out1 out2))
                   (when words
                     {:words (proc-aligned-doc
                              out1 out2 idx sword1 eword1 doc-data sword2 eword2)})
                   {:text1 (subs (:text idx) start stop)
                    ;; Character offsets in reference text used by highbrow
                    :start start
                    :stop stop
                    :sword1 sword1 :eword1 eword1
                    :sword2 sword2 :eword2 eword2
                    :len1 (- eword1 sword1)
                    :len2 (- eword2 sword2)
                    :prop1 (double (/ (- eword1 sword1) term-count))
                    :prop2 (double (/ (- eword2 sword2) n2))
                    :cites
                    (mapv #(get (:names idx) %) (distinct (subvec (:positions idx) sword1 eword1)))
                    :align1 out1
                    :align2 out2}))
                (catch Exception ex
                  (binding [*out* *err*]
                    (println ex page score s e min2 max2 spans))
                  nil)
                (catch OutOfMemoryError ex
                  (binding [*out* *err*]
                    (println ex page score s e min2 max2 spans))
                  nil))))
           good-spans))))

;; (def idx (index-tokens (load-tsv "/home/dasmith/work/locca/check/Hawthorne_RillFromtheTownPump.cts") 5))
;; (def ri (RetrievalFactory/instance "/home/dasmith/work/locca/c19-b71-moa/idx" (Parameters.)))
;; (proc-page idx ri nil 0 false ["nora_nora0045-1" [[677.0295313074407 0 2487 29244 79357]]])

;; We should include the canonical texts themselves in the index so
;; that their ngrams show up as occurring at least once.  We should
;; therefore also remove hits to these texts from the results below.
(defn quoted-passages
  [docs gram bad-docs ^KeyIterator ki ^Retrieval ri lm
   {:keys [max-count max-gap min-score raw words]}]
  (let [idx (index-tokens docs gram)
        page-hits (get-page-hits idx ki ri max-count max-gap bad-docs)
        ;; book-hits (frequencies (map (comp first doc-id-parts first) page-hits))
        ]
    (if raw
      page-hits
      ;; We keep a single record for each page, with multiple
      ;; spans, so we can save time and look up the text for a
      ;; page once.
      (mapcat (partial proc-page idx ri lm min-score words) (sort page-hits)))))

(defn- get-bad-docs
  [dir]
  (->> (jio/file dir "names") str dump-index
       (filter #(re-find #"^urn:cts:" (second %)))
       (map #(Long/parseLong (first %)))
       set))

(defn dump-quotes
  "Find passages in a reference text that align well to passages using an an n-gram index."
  [& argv]
  (let [[options remaining banner]
        (safe-cli argv
                  (str
                   "passim quotes [options] <n-gram index> (<reference text file> | -)+\n\n"
                   (var-doc #'dump-quotes))
                  ["-c" "--max-count" "Maximum n-gram count to use" :default 1000 :parse-fn #(Integer/parseInt %)]
                  ["-g" "--max-gap" "Maximum gap in n-gram hits within a passage" :default 200 :parse-fn #(Integer/parseInt %)]
                  ["-s" "--min-score" "Minimum score for n-gram matches" :default 0 :parse-fn #(Double/parseDouble %)]
                  ["-p" "--pretty" "Pretty-print JSON output" :default false :flag true]
                  ["-r" "--raw" "Output raw span information" :default false :flag true]
                  ["-w" "--words" "Output alignments at the word level" :default false :flag true]
                  ["-l" "--lm" "Language model binary" :default nil]
                  ["-h" "--help" "Show help" :default false :flag true])]
    (try
      (let [[idx & tfiles] remaining
            printer (if (:pretty options)
                      #(json/pprint % :escape-slash false)
                      #(json/write % *out* :escape-slash false))
            dir (.getParent (java.io.File. idx))
            bad-docs (get-bad-docs dir)
            di (DiskIndex/openIndexPart idx)
            lm (when (:lm options)
                 (ArrayEncodedCachingLmWrapper/wrapWithCacheNotThreadSafe
                  (LmReaders/readLmBinary (:lm options))))
            gram (.get (.getManifest di) "n" 5)
            ki (.getIterator di)
            ri (RetrievalFactory/instance dir (Parameters.))]
        (doseq [f (if (seq tfiles) tfiles ["-"])
                q (-> (if (= "-" f) *in* f)
                      load-tsv
                      (quoted-passages gram bad-docs ki ri lm options))]
          (printer q)
          (println)))
      (catch Exception e
        (binding [*out* *err*]
          (println e)
          (exit 1 banner))))))

(defn json-seq
  "Returns JSON records from rdr as a lazy sequence.
  rdr must implement java.io.BufferedReader."
  [^java.io.BufferedReader rdr]
  (when-let [rec (json/read rdr :key-fn keyword :eof-error? false)]
    (cons rec (lazy-seq (json-seq rdr)))))

(defn- qoac-quote
  [rec]
  (let [id
        (if-let
            [[_ base series page] (re-find #"^(.*)/download/(.*)/(page/leaf[^_]+)" (:url rec))]
          (str base "/stream/" series "#" page "/mode/1up")
          (:url rec))]
    {"@context" "http://www.w3.org/ns/oa-context-20130208.json"
     "@type" "oa:Annotation"
     "hasTarget" {
                  "@type" "oa:Choice"
                  "oa:default" {
                                "@type" "oa:SpecificResource"
                                "hasSource"
                                {
                                 "@id" id
                                 "@type" "dctypes:Text"
                                 "dc:title" (:title rec)
                               "dc:date" (:date rec)
                               "dc:language" (:language rec)
                               }
                              "hasSelector"
                              {
                               "@type" "oa:TextQuoteSelector"
                               "prefix" (:prefix2 rec)
                               "exact" (:text2 rec)
                               "suffix" (:suffix2 rec)
                               }
                              }
                  "oa:item" {"@id" (:url rec) "@type" "dctypes:Image"}
                  }
     "hasBody"
     {
      "@type" "oa:SpecificResource"
      "hasSource" {"@type" "oa:Composite"
                   "oa:item" (vec (:cites rec))}
      "hasSelector" {
                     "@type" "oa:TextQuoteSelector"
                     "exact" (:text1 rec)
                     }
      }
     }))

(defn qoac
  "Convert to Open Annotation JSON-LD serialization."
  [& argv]
  (doseq [q (->> *in* jio/reader json-seq)]
    (json/write (qoac-quote q) *out* :escape-slash false)
    (println)))

;; (def raw (load-tsv "/Users/dasmith/cts/urn:cts:englishLit:shakespeare.ham"))
;; (def docs (take 20 raw))
;; (def di (DiskIndex/openIndexPart "/Users/dasmith/cts/ham/od.n5.w1.h2.df.pos"))
;; (def ki (.getIterator di))
;; (def ri (RetrievalFactory/instance "/Users/dasmith/cts/ham" (Parameters.)))
;; (def idx (index-tokens docs 5))
