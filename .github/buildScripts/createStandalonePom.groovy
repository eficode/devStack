/**
 * Creates a new pom-standalone.xml based on pom.xml but with new artifact name (devstack-standalone) and
 * additional build steps for shading based on shadingConf.xml
 */
String projectBasePath = project.basedir
println("Base dir:" + projectBasePath)
File origPom = new File(projectBasePath + "/pom.xml")
File shadingConf = new File(origPom.parentFile.parentFile.absolutePath + "/.github/buildScripts/shadingConf.xml")


String newPomBody = origPom.text.replace("<plugins>", "<plugins>\n" + shadingConf.text)

newPomBody = newPomBody.replaceFirst("<artifactId>devstack<\\/artifactId>", "<artifactId>devstack-standalone<\\/artifactId>")

File standalonePom = new File(projectBasePath + "/devstack-standalone"  + "/pom-standalone.xml")
standalonePom.mkdirs()
standalonePom.createNewFile()
standalonePom.text = newPomBody

println("Created:" + standalonePom.absolutePath)