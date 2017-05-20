package mesosphere.marathon

/**
  * Thrown for errors during [[Normalization]]. Validation should normally be checking for invalid data structures
  * that would lead to errors during normalization, so these exceptions are very unexpected.
  * @param msg provides details
  */
case class NormalizationException(msg: String) extends Exception(msg)
