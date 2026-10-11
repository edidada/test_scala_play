ThisBuild / scalaVersion := "3.3.8"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := "ecshop-play",
    ThisBuild / organization := "com.github.somessyy",
    // sbt 1.x + Scala 3 Play project: keep the default injected routes generator.
    routesGenerator := InjectedRoutesGenerator,
    libraryDependencies ++= Seq(
      guice,
      jdbc,
      evolutions,
      "org.xerial"  % "sqlite-jdbc"    % "3.53.4.0",
      "com.mysql"   % "mysql-connector-j" % "9.5.0",
      "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test
    ),
    scalacOptions ++= Seq("-feature", "-Wunused:imports"),
    // Play 3.0 + Pekko: silence noisy binary-compatibility evictions.
    ThisBuild / evictionErrorLevel := Level.Warn
  )
