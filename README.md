To build this you are also going to need the apache http cliches from
https://github.com/mutantbob/http-cliches

After doing an `mvn install` for the apache http cliches you should be
able to `mvn package` in this directory.  That will get you the `target/dollPartsReceiver-1.0-SNAPSHOT.jar` .  To run the daemon:

    java -DdollParts.directory=/var/spool/dollparts/ -jar target/dollPartsReceiver-1.0-SNAPSHOT.jar

After that, you must read jr's mind to know how to properly use the
software.
