import com.typesafe.sbt.SbtScalariform._
import scalariform.formatter.preferences._


object Format {
  lazy val settings = scalariformSettings ++ Seq(ScalariformKeys.preferences := formattingPreferences)

  val formattingPreferences = FormattingPreferences()
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(DoubleIndentClassDeclaration, true)
}