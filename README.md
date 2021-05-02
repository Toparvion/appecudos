# jCuDoS – An AppCDS automation toolchain for Spring Boot

jCuDos is a set of CLI tools for simplifying the use of [Application Class Data Sharing](https://docs.oracle.com/en/java/javase/11/tools/java.html#GUID-31503FCE-93D0-4175-9B4F-F6A738B2F4C4) to multiple Spring Boot applications of the same project. Usually such applications have quite large common subset of the same classes (from JDK to Spring to application libraries) what makes the leveraging of AppCDS beneficial from both startup time and (especially) memory footprint point of view.

## Project status

*:warning: **The project is not completed and the work is discontinued.** It implements the very basic algorithm (see below) and needs some additional work to become production-ready. Please feel free to support its further development by forking, making an issue/PR, documenting or just giving the feedback.*

## Description

jCuDoS consists of several useful commands which can be called either separately or together (by means of the root `jcudos` command). In the last case, they will perform the following algorithm. 

## The algorithm of AppCDS preparation for a set of Spring Boot microservices of the same project

### Preconditions

* There is a set of Spring Boot “fat” JARs from all the applications (microservices);
* All the JARs reside on the same filesystem (not necessary in the same directory);
* All the microservices were at least once launched with the `-XX:DumpLoadedClassList` JVM option.

### Input Data

1. A [Glob](https://en.wikipedia.org/wiki/Glob_(programming)) to the lists of classes gathered with  `-XX:DumpLoadedClassList`;
1. A [Glob](https://en.wikipedia.org/wiki/Glob_(programming)) to “fat” JARs;
1. Desired path to a common output directory (the following steps assume `_appcds`).

### The algorithm steps

#### A. Processing class lists

1. Find out the common part of the lists;
1. Save it into `_appcds/_shared/list/classes.list` :zero:.

#### B. Processing of each “fat” JAR

1. Detect the name of the start class.
   If no name is detected (i.e. the JAR is not a Spring Boot one), skip the archive.
   Remember the name of the application as “raw” class name in lower case (referring later as `<appname>`, for example class named `com.example.MyCoolApp` yields `mycoolapp` application name).
1. Store the start class name into  `_appcds/<appName>/start-class.txt` :one:.
1. Extract the libraries of the current “fat” JAR into `_appcds/<appName>/lib/`.
1. Convert current “fat” JAR into `_appcds/<appName>/lib/<appName>.slim.jar` :two:.

#### C. Creation of the common archive

1. Find out the common libraries names from listings of each `_appcds/<appName>/lib/` directory.

1. Copy all the found libraries into `_appcds/_shared/lib/`.

1. Compose the `_appcds/_shared/list/classpath.arg` :five: file from absolute paths of the copied​ libraries.

1. Remember the list of absolute paths of// the common libraries :three:.

1. Navigate to `_appcds` directory and execute there:

   ```bash
   java -Xshare:dump \
        -XX:SharedClassListFile=_shared/list/classes.list \
        -XX:SharedArchiveFile=_shared/jsa/classes.jsa \
        @_shared/list/classpath.arg &> log/dump.log
   ```

   Here were used: `_appcds/_shared/list/classes.list` :zero: and `_appcds/_shared/list/classpath.arg` :five:.

#### D. Preparation of microservices for launching

1. Remove all the common libraries :three: from the each `_appcds/<appName>/lib/` directory.
1. Compose `_appcds/<appName>/appcds.arg` :four:  file from:
   1. `-XX:SharedArchiveFile=_appcds/_shared/jsa/classes.jsa` option;
   1. `-classpath` option which in turn consists of:
      1. common libraries list :three:;
      1. own libraries list taken as `_appcds/<appName>/lib/` directory listing.
         The slim JAR `_appcds/<appName>/lib/<appName>.slim.jar` :two: should be included there as well;
      1. the name of the start class :one:.

### Launching a microservice with AppCDS (out of the algorithm’s scope)

* In the launch script, just replace the  `-jar <appName>.jar` with `@appcds.arg` :four:.

---

### Want to know more?

:heavy_plus_sign: There is much more information about the project like examples, statistics, observations, comparison tables, etc. Please [contact me](https://toparvion.pro/en/) if it interests you.
