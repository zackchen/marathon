package mesosphere.marathon
package api.v2.validation

import com.wix.accord.Validator
import com.wix.accord.dsl._
import mesosphere.marathon.raml.{ EnvVarValueOrSecret, Environment }
import mesosphere.{ UnitTest, ValidationTestLike }

class EnvVarValidationTest extends UnitTest with ValidationTestLike {

  import EnvVarValidationMessages._

  "EnvVarValidation" when {
    "an environment is validated" should {

      def compliantEnv(title: String, m: Map[String, EnvVarValueOrSecret], strictNameValidation: Boolean = true): Unit = {
        s"$title is compliant with validation rules" in new WithoutSecrets(strictNameValidation) {
          shouldSucceed(m)
        }
      }

      behave like compliantEnv("empty env", Map.empty)
      behave like compliantEnv("singleton env", Environment("foo" -> "bar"))
      behave like compliantEnv("mixed case env", Environment("foo" -> "bar", "FOO" -> "BAR"))
      behave like compliantEnv("underscore env", Environment("_" -> "x"))
      behave like compliantEnv("alpha numerical env", Environment("a_1_" -> "x"))
      behave like compliantEnv("whitespace env", Environment(" " -> "x"), strictNameValidation = false)
      behave like compliantEnv("hyphen env", Environment("-" -> "x"), strictNameValidation = false)
      behave like compliantEnv("numerical env", Environment("9" -> "x"), strictNameValidation = false)

      "fail with a numerical env variable name" in new WithoutSecrets {
        shouldViolate(Wrapper(Environment("9" -> "x")), "/env(0)", MustContainOnlyAlphanumeric)
      }

      def failsWhenExpected(subtitle: String, strict: Boolean): Unit = {
        s"fail with empty variable name $subtitle" in new WithoutSecrets(strict) {
          shouldViolate(Wrapper(Environment("" -> "x")), "/env(0)", "must not be empty")
          if (strict) shouldViolate(Wrapper(Environment("" -> "x")), "/env(0)", MustContainOnlyAlphanumeric)
        }

        s"fail with too long variable name $subtitle" in new WithoutSecrets(strict) {
          val name = "x" * 255
          if (strict) {
            shouldViolate(Wrapper(Environment(name -> "x")), "/env(0)", MustContainOnlyAlphanumeric)
          } else {
            shouldSucceed(Wrapper(Environment(name -> "x")))
          }
        }
      }

      behave like failsWhenExpected("(strict)", strict = true)
      behave like failsWhenExpected("(non-strict)", strict = false)
    }
  }

  class WithoutSecrets(strictNameValidation: Boolean = true) {
    implicit lazy val envVarValidation: Validator[Map[String, EnvVarValueOrSecret]] =
      EnvVarValidation.envValidator(strictNameValidation, Map.empty, Set.empty)

    case class Wrapper(env: Map[String, EnvVarValueOrSecret])

    object Wrapper {
      implicit val wrapperValidation: Validator[Wrapper] = validator { wrapper =>
        wrapper.env is envVarValidation // invoked here the same way that it is for apps and pods
      }
    }
  }
}
