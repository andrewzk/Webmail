#/usr/bin/bash

if [ ! -d "bin" ]
then
	mkdir bin
fi

javac @files -d ./bin
