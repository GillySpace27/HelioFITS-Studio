*************************************
*  JHelioviewer Build Instructions  *
*************************************

JHelioviewer is written in Java. In order to build the whole project you need
Apache Ant.

JHelioviewer uses the git version control system in order to keep track of
revisions and releases.

The source code is hosted on GitHub:

https://github.com/Helioviewer-Project/JHelioviewer-SWHV

Build instructions
-------------------

I) Get the source code. There are two alternatives:

    - https://github.com/Helioviewer-Project/JHelioviewer-SWHV.git

   a) Get the latest trunk using git:

        1.) Install git

        2.) Choose a destination directory for the source code

        3.) Open a terminal and type:

            git clone https://github.com/Helioviewer-Project/JHelioviewer-SWHV.git ${PATH_TO_DESTINATION_DIRECTORY}

   b) Get the source code of the latest trunk from
            https://github.com/Helioviewer-Project/JHelioviewer-SWHV/archive/refs/heads/master.zip
      and extract the archive to a directory of your choice

II) Build the source code with Ant (http://ant.apache.org/)

    1.) Open a terminal and change to the directory containing the source code
        and the ant buildfile (build.xml)

    2.) The following ant targets exist:

           ant                   - just builds HFStudio.jar

           ant test              - compile and run every self-check in extra/test

           ant prone             - run Error Prone static analysis tool on the source tree

           ant clean             - delete temporary build files

    3.) The Java Development Kit version 25 or later has to be installed and present in the PATH.
        build.xml compiles with release="25", so an older JDK cannot build this at all.

    4.) After the source is built, the program can be run by executing the following
    command:

        java --enable-native-access=ALL-UNNAMED --add-exports java.desktop/sun.awt=ALL-UNNAMED --add-exports java.desktop/sun.swing=ALL-UNNAMED -jar HFStudio.jar

        Or use the launchers that ship beside it: run.command (macOS), run.sh, run.bat.
