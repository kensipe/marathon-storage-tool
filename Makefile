.SECONDARY:

artifacts/marathon-%.tgz:
	mkdir -p artifacts/
	curl -f -o $@.tmp http://downloads.mesosphere.com/marathon/v$*/marathon-$*.tgz
	mv $@.tmp $@

targets/%/marathon.jar: artifacts/marathon-%.tgz
	mkdir -p targets/$*
	mkdir -p tmp/marathon-$*/; cd tmp/marathon-$*; tar xzf ../../artifacts/marathon-$*.tgz
	find tmp/marathon-$*/ -name "marathon-*.jar" -exec mv {} $@ \;
	rm -rf tmp/marathon-$*
	[ -f $@ ] && touch $@

targets/%/lib: 
	mkdir -p $@

# Ammonite keeps a compile cache around, and this compile cache is to be built against a different jar file
# In order to potential confusion here, we create a copy of each of these files so each can be linked against the respective jar file
targets/%/lib/predef.sc: targets/%/lib lib/predef.sc
	cp lib/predef.sc $@

targets/%/lib/load-jar.sc: targets/%/lib lib/load-jar.sc
	cp lib/load-jar.sc $@

targets/%/lib/storage.sc: targets/%/lib lib/storage.sc
	cp lib/storage.sc $@

targets/%/bin/storage-tool.sh: targets/%/lib bin/storage-tool.sh
	mkdir -p $(@D)
	cp bin/storage-tool.sh $@

targets/%/verified: targets/%/lib/storage.sc targets/%/lib/load-jar.sc targets/%/lib/predef.sc targets/%/marathon.jar
	cd targets/$*; amm-2.11 --predef lib/predef.sc --predef-code 'println("it worked"); sys.exit(0)' | grep "it worked"
	touch $@

targets/%/Dockerfile: Dockerfile
	mkdir -p $(@D)
	cp $< $@

targets/%/docker-built: targets/%/verified targets/%/bin/storage-tool.sh targets/%/Dockerfile
	cd $(@D); docker build . -t mesosphere/marathon-storage-tool:$*
	docker push mesosphere/marathon-storage-tool:$*
	touch $@
