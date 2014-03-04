## There are several dummy targets. Most interesting are:
## make align
## make clinfo

## This is the subdirectory where your input files live in "input".
## Edit this if you have only one collection.
## Otherwise, pass it as an argument to make, e.g.:
## make COLL=bills align
COLL=coll

LC_ALL=POSIX
export LC_ALL

SHELL=/bin/bash -o pipefail -c
#.SHELLFLAGS=-o pipefail -c
TMPDIR=/tmp

#QRUN=qrsh -nostdin -N $(shell basename $@) -now n -j y -cwd -V -l mem_token=2G -l mem_free=2G
QRUN=bsub -K
PSH=bash -o pipefail -c

#LQRUN=qrsh -nostdin -N $(shell basename $@) -now n -j y -cwd -V -l mem_token=2G -l mem_free=2G -l long=TRUE
LQRUN=bsub -K

passim=passim
passimjar=$(shell which $(passim))
passimopt=-Dfile.encoding=UTF-8 -jar $(passimjar)

CLASSPATH_PREFIX:=$(passimjar):$(CLASSPATH_PREFIX)
export CLASSPATH_PREFIX

UPPER=100
NGRAM=5
MAXDF=$(UPPER)
MODP=1
MODREC=1
GAP=1
WIDTH=1

MINREP=5

ifeq ($(MODP),1)
pairs=pairs-n$(NGRAM)u$(UPPER)
else
MINREP=1
pairs=pairs-n$(NGRAM)u$(UPPER)p$(MODP)
endif

ifneq ($(MODREC),1)
pairs:=$(pairs)r$(MODREC)
MINREP=1
endif

ifneq ($(MAXDF),$(UPPER))
pairs:=$(pairs)d$(MAXDF)
endif

ifeq ($(NGRAM),1)
idx=$(COLL)/idx/postings
else
idx=$(COLL)/idx/od.n$(NGRAM).w1.h2.df.pos
endif

stops=

ifneq ($(GAP),1)
pairs:=$(pairs)w$(WIDTH)g$(GAP)
idx=$(COLL)/idx/od.n$(NGRAM).w$(WIDTH).h2.g$(GAP).s5.df.pos
stops=--stop $(COLL)/df05.stop
endif

ifneq ($(MINREP),5)
pairs:=$(pairs)m$(MINREP)
endif

dir=$(COLL)/$(pairs)

MINALG=20
RELOVER=0.5
MAXREP=4

cluster=$(dir)/cl$(MINALG)-flover$(subst .,,$(RELOVER))-rep$(MAXREP).cluster.gz
clinfo=$(patsubst %.cluster.gz,%.clinfo.gz,$(cluster))

.PHONY:	clinfo cluster swalign align pairs

.SECONDARY:

clinfo:	$(clinfo)

cluster:	$(cluster)

align:	$(dir)/pall.proc.srt.gz

swalign:	$(dir)/pall.sw.srt.gz

pairs:	$(dir)/pall.srt.gz

# .DELETE_ON_ERROR:

GDISTRIB=$(shell ls -1 $(COLL)/input/*.gz | wc -l)

%/idx/postings:
	cd $*; ../lsf-galago build ${CURDIR}/build.json --distrib=$(GDISTRIB) --galagoJobDir=$(notdir $@).tmp > build.sh; . build.sh

$(idx):	$(dir $(idx))postings
	cd $(COLL); ../lsf-galago build-window ${CURDIR}/window.json --n=$(NGRAM) --width=$(WIDTH) --gap=$(GAP) --distrib=$(GDISTRIB) --galagoJobDir=$(notdir $@).tmp > window.sh; . window.sh

$(dir)/pall.srt.gz:	$(dir)/od.siz $(idx)
	mkdir -p $(dir)/pall.srt.tmp
	bsub -J "$(dir)/pall.srt[1-$$(( `cat $<` + 1 ))]%50" -eo "$(dir)/pall.srt.tmp/err%I" \
	    'ofile=$(dir)/pall.srt.tmp/out`printf "%03d" $$(( \$$LSB_JOBINDEX - 1 ))`; [ -f $$ofile ] || ${passim} pairs $(stops) --max-series $(UPPER) --max-df $(MAXDF) --modp $(MODP) --modrec $(MODREC) --step $$(( $$LSB_JOBINDEX - 1 )) --stride 1000000 $(idx) | sort -T $(TMPDIR) > $$ofile'
	bsub -K -w "$(dir)/pall.srt" 'ls -1 $(dir)/pall.srt.tmp/out* | xargs sort -T $(TMPDIR) -m | ${passim} merge -m $(MINREP) | gzip -c > $@'

algram=$(NGRAM)
ifneq ($(GAP),1)
algram=1
endif

$(dir)/pall.proc.srt.gz:	$(dir)/pall.srt.gz
	mkdir -p $(dir)/pall.proc.tmp
	[ -f $(dir)/pall.proc.tmp/in000 ] || zcat -f $< | split -d -a 3 -l 20000 - $(dir)/pall.proc.tmp/in
	bsub -R 'mem>6000' -J "$(dir)/pall.proc[1-`ls -1 $(dir)/pall.proc.tmp/in* | wc -l`]%50" -eo "$(dir)/pall.proc.tmp/err%I" \
	    'ofile=$(dir)/pall.proc.tmp/out`printf "%03d" $$(( \$$LSB_JOBINDEX - 1 ))`; [ -f $$ofile ] || java -Xmx5900m ${passimopt} scores -n $(algram) $(dir)/../idx < $(dir)/pall.proc.tmp/in`printf "%03d" $$(( \$$LSB_JOBINDEX - 1 ))` | awk "\$$1 >= 10" | sort -rn > $$ofile'
	bsub -K -w "$(dir)/pall.proc" 'ls -1 $(dir)/pall.proc.tmp/out* | xargs sort -rnm | gzip -c > $@'

$(dir)/pall.sw.srt.gz:	$(dir)/pall.srt.gz
	mkdir -p $(dir)/pall.sw.tmp
	[ -f $(dir)/pall.sw.tmp/in000 ] || zcat -f $< | split -d -a 3 -l 20000 - $(dir)/pall.sw.tmp/in
	bsub -R 'mem>8000' -J "$(dir)/pall.sw[1-`ls -1 $(dir)/pall.sw.tmp/in* | wc -l`]%50" -eo "$(dir)/pall.sw.tmp/err%I" \
	    'ofile=$(dir)/pall.sw.tmp/out`printf "%03d" $$(( \$$LSB_JOBINDEX - 1 ))`; [ -f $$ofile ] || java -Xmx7900m ${passimopt} scores -n 0 $(dir)/../idx < $(dir)/pall.sw.tmp/in`printf "%03d" $$(( \$$LSB_JOBINDEX - 1 ))` | awk "\$$1 >= 10" | sort -rn > $$ofile'
	bsub -K -w "$(dir)/pall.sw" 'ls -1 $(dir)/pall.sw.tmp/out* | xargs sort -rnm | gzip -c > $@'

%.clinfo.gz:	%.cluster.gz
	mkdir -p $*.clinfo.tmp
	[ -f $*.clinfo.tmp/in000 ] || zcat -f $< | split -d -a 3 -l 2000 - $*.clinfo.tmp/in
	bsub -J "$*.clinfo[1-`ls -1 $*.clinfo.tmp/in* | wc -l`]%50" -eo "$*.clinfo.tmp/err%I" \
	    'ofile=$*.clinfo.tmp/out`printf "%03d" $$(( \$$LSB_JOBINDEX - 1 ))`; [ -f $$ofile ] || ${passim} format $(dir $@)/../idx < $*.clinfo.tmp/in`printf "%03d" $$(( \$$LSB_JOBINDEX - 1 ))` > $$ofile'
	bsub -K -w "$*.clinfo" 'ls -1 $*.clinfo.tmp/out* | xargs cat | gzip -c > $@'

$(cluster):	$(dir)/pall.proc.srt.gz
	$(QRUN) -eo $@.err 'gzip -dc $< | awk "\$$1 >= $(MINALG)" | java -Xmx8g ${passimopt} cluster --relative-overlap $(RELOVER) --max-repeats $(MAXREP) | gzip -c > $@'

%.idtab.gz:	%.clinfo.gz
	$(QRUN) 'gzip -dc $< | ${passim} format $(dir $@)/../idx | sort -k1,1n -k3,4 | gzip -c > $@'

$(dir)/od.siz:	$(idx)
	mkdir -p $(dir)
	galago dump-index-manifest $< | grep "keyCount" | perl -lne 'print int($$1 / 1000000) if /(\d+)/' > $@

%/df05.stop:	%/idx/postings
	galago dump-term-stats $< | awk "\$$3 >= $$(( `galago dump-index-manifest $*/idx/names | grep "keyCount" | perl -lane 'print $$1 if / (\d+),/'` / 2 ))" | sort -k3,3rn -k2,2rn | cut -f 1 > $@

%/df01.stop:	%/idx/postings
	galago dump-term-stats $< | awk "\$$3 >= $$(( `galago dump-index-manifest $*/idx/names | grep "keyCount" | perl -lane 'print $$1 if / (\d+),/'` / 10 ))" | sort -k3,3rn -k2,2rn | cut -f 1 > $@
