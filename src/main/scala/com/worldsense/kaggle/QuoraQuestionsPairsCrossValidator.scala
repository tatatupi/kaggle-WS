package com.worldsense.kaggle

import com.worldsense.kaggle.QuoraQuestionsPairsCrossValidator.LogLossBinaryClassificationEvaluator
import org.apache.spark.ml.Estimator
import org.apache.spark.sql.functions.col
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.clustering.LDA
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.feature.{CountVectorizer, StopWordsRemover}
import org.apache.spark.ml.linalg.{Vector, VectorUDT}
import org.apache.spark.ml.param.{Param, ParamMap, ParamValidators}
import org.apache.spark.ml.tuning.{CrossValidator, CrossValidatorModel, ParamGridBuilder}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import org.apache.spark.sql.types.{DoubleType, StructType}

import scala.io.Source

class QuoraQuestionsPairsCrossValidator(override val uid: String) extends Estimator[CrossValidatorModel] {
  def this() = this(Identifiable.randomUID("quoraquestionspairscrossvalidator"))
  val numFolds = 2
  private val logger = org.log4s.getLogger

  final val vocabularySize: Param[List[Int]] = new Param[List[Int]](this, "vocabularySize", "size of vocabulary")
  def setVocabularySize(value: List[Int]): this.type = set(vocabularySize, value)
  setDefault(vocabularySize, List(10000))

  final val stopwords: Param[List[String]] = new Param[List[String]](this, "stopwords", "path to files with comma separate normalized stopwords")
  def setStopwords(value: List[String]): this.type = set(stopwords, value)
  setDefault(stopwords, List("src/main/resources/quora/stopwords.txt"))

  final val numTopics: Param[List[Int]] = new Param[List[Int]](this, "numTopics", "comma separate input column names")
  def setNumTopics(value: List[Int]): this.type = set(numTopics, value)
  setDefault(numTopics, List(20))

  final val minDF: Param[List[Double]] = new Param[List[Double]](this, "minDF", "comma separate input column names")
  def setMinDF(value: List[Double]): this.type = set(minDF, value)
  setDefault(minDF, List(2.0))

  final val ldaMaxIter: Param[List[Int]] = new Param[List[Int]](this, "ldaMaxIter", "comma separate input column names")
  def setLdaMaxIter(value: List[Int]): this.type = set(ldaMaxIter, value)
  setDefault(ldaMaxIter, List(100))

  final val logisticRegressionMaxIter: Param[List[Int]] = new Param[List[Int]](this, "logisticRegressionMaxIter", "comma separate input column names")
  def setLogisticRegressionMaxIter(value: List[Int]): this.type = set(logisticRegressionMaxIter, value)
  setDefault(logisticRegressionMaxIter, List(100))

  override def transformSchema(schema: StructType): StructType = assembleCrossValidator().transformSchema(schema)
  override def fit(dataset: Dataset[_]): CrossValidatorModel = {
    assembleCrossValidator().fit(dataset)
  }
  def copy(extra: ParamMap): QuoraQuestionsPairsCrossValidator = defaultCopy(extra)

  private def assembleCrossValidator(): CrossValidator = {
    val stopwordsRemover = new StopWordsRemover()
    val countVectorizer = new CountVectorizer()
    val logisticRegression = new LogisticRegression()
    val lda = new LDA()
    val estimator = new QuoraQuestionsPairsPipeline()
      .setStopwordsRemover(stopwordsRemover)
      .setCountVectorizer(countVectorizer)
      .setLogisticRegression(logisticRegression)
      .setLDA(lda)
    // Grid search on hyperparameter space
    val stopwordsLists = $(stopwords).map(Source.fromFile).map(_.getLines().mkString(",").split(","))
    val paramGrid = new ParamGridBuilder()
      .addGrid(stopwordsRemover.stopWords, stopwordsLists)
      .addGrid(countVectorizer.vocabSize, $(vocabularySize))
      .addGrid(lda.k, $(numTopics))
      .addGrid(countVectorizer.minDF, $(minDF))
      .addGrid(lda.maxIter, $(ldaMaxIter))
      .addGrid(logisticRegression.maxIter, $(logisticRegressionMaxIter))
      .build()

    val evaluator = new BinaryClassificationEvaluator()
      .setLabelCol("isDuplicateLabel")
        .setRawPredictionCol("raw")
        .setMetricName("areaUnderROC")

    // Cross-validation setup
    new CrossValidator()
        .setEstimator(estimator)
        .setEvaluator(evaluator)
        .setEstimatorParamMaps(paramGrid)
        .setNumFolds(numFolds)
  }
}

object QuoraQuestionsPairsCrossValidator {
  def logLoss(scoreAndLabels: RDD[(Double, Double)]): Double = {
    val v = scoreAndLabels.collect()
    // https://www.kaggle.com/wiki/LogLoss
    v.map(pl => pl._2 * math.log(pl._1)).sum / v.length * -1
  }

  class LogLossBinaryClassificationEvaluator(uid: String) extends BinaryClassificationEvaluator(uid) {
    def this() = this(Identifiable.randomUID("logLossEval"))
    override def evaluate(dataset: Dataset[_]): Double = {
      val scoreAndLabels: RDD[(Double, Double)] =
        dataset.select(col($(rawPredictionCol)), col($(labelCol)).cast(DoubleType)).rdd.map {
          case Row(rawPrediction: Vector, label: Double) => (rawPrediction(1), label)
          case Row(rawPrediction: Double, label: Double) => (rawPrediction, label)
        }
      logLoss(scoreAndLabels)
    }
  }
}
