package com.worldsense.kaggle

import org.apache.spark.ml.{Estimator, Pipeline, PipelineModel, PipelineStage}
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.clustering.{LDA, LDAModel}
import org.apache.spark.ml.feature._
import org.apache.spark.ml.linalg.DenseVector
import org.apache.spark.ml.param.{Param, ParamMap}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.mllib.evaluation.{BinaryClassificationMetrics, MultilabelMetrics}
import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.functions.{udf, col}


class QuoraQuestionsPairsPipeline(override val uid: String) extends Estimator[PipelineModel] {
  val tokenizer: Param[RegexTokenizer] =
    new Param(this, "tokenizer", "Breaks the sentences into individual words.")
  setDefault(tokenizer, new RegexTokenizer().setPattern("""[\p{Punct} ]"""))
  val stopwordsRemover: Param[StopWordsRemover] =
    new Param(this, "stopwords", "Drops stopwords from input text.")
  setDefault(stopwordsRemover, new StopWordsRemover())
  val countVectorizer: Param[CountVectorizer] =
    new Param(this, "countVectorizer", "Converts words into numerical ids.")
  setDefault(countVectorizer, new CountVectorizer)
  val idf: Param[IDF] =
    new Param(this, "idf", "Calculate weights for vector representation of tokens.")
  setDefault(idf, new IDF())
  val lda: Param[LDA] =
    new Param(this, "lda", "Convert each question into a weighted topic vector.")
  setDefault(lda, new LDA())
  val logisticRegression: Param[LogisticRegression] =
    new Param(this, "logisticRegression", "Combine question vectors pairs into a predicted probability.")
  setDefault(logisticRegression, new LogisticRegression())
  private val questionsCols = Array("question1", "question2")

  def this() = this(Identifiable.randomUID("quoraquestionspairspipeline"))

  override def transformSchema(schema: StructType): StructType = assemblePipeline().transformSchema(schema)

  private def assemblePipeline(): Pipeline = {
    val stages = Array(
      cleanerPipeline(),
      tokenizePipeline(),
      vectorizePipeline(),
      ldaPipeline(),
      logisticRegressionPipeline()
    ).flatten
    new Pipeline().setStages(stages)
  }
  override def fit(dataset: Dataset[_]): PipelineModel = {
    logger.info(s"Preparing to fit quora question pipeline with params:\n${explainParams()}")
    val model = assemblePipeline().fit(dataset)
    logMetrics(model, dataset)
    logTopics(dataset.sparkSession, model)
    model
  }
  private val logger = org.log4s.getLogger
  private def logMetrics(model: PipelineModel, dataset: Dataset[_]): Unit = {
    import dataset.sparkSession.implicits.newProductEncoder
    val labeledDataset = model.transform(dataset).select("p", "isDuplicateLabel").as[(DenseVector, Int)]
    labeledDataset.cache()  // will be used multiple times
    val scoresAndLabels = labeledDataset map { case (p, label) =>
      (p.values.last, label.toDouble)  // the last of values is the probability of the positive class
    }
    val binaryMetrics = new BinaryClassificationMetrics(scoresAndLabels.rdd)
    val areaUnderPR = binaryMetrics.areaUnderPR()
    val areaUnderROC = binaryMetrics.areaUnderROC()
    val predictionAndLabels = labeledDataset map { case (p, label) =>
      (Array(p.values.last.round.toDouble), if (label > 0) Array(1.0) else Array(0.0))
    }
    val multiLabelMetrics = new MultilabelMetrics(predictionAndLabels.rdd)
    logger.info(s"Trained a model with area under pr $areaUnderPR and area under roc curve $areaUnderROC, " +
                s"accuracy ${multiLabelMetrics.accuracy} and f1 ${multiLabelMetrics.f1Measure}.\n" +
                s"Params are: ${explainParams()}")
    labeledDataset.unpersist()
  }
  private def logTopics(spark: SparkSession, model: PipelineModel): Unit = {
    val vocabulary = getNestedMcModel[CountVectorizerModel](model, 3).vocabulary
    val topicsDF = getNestedMcModel[LDAModel](model, 5).describeTopics()
    val bc = spark.sparkContext.broadcast(vocabulary)
    val getTerm = udf((indices: Seq[Int]) => indices.map(i => bc.value(i)))
    val topics = topicsDF.withColumn("terms", getTerm(col("termIndices"))).collect
    val explained = topics.map { row =>
      val topic = row.getAs[Int]("topic")
      val terms = row.getAs[Seq[String]]("terms")
      val termWeights = row.getAs[Seq[Double]]("termWeights")
      val tw = terms.zip(termWeights).map(tw => f"${tw._1} ${tw._2}%.3f").mkString(" ")
      s"$topic: $tw"
    }
    bc.unpersist()
    val msg = explained.mkString("\n")
    logger.info(s"LDA model topics are:\n$msg")
  }

  private def getNestedMcModel[T](model: PipelineModel, index: Int): T = {
    model.stages(index).asInstanceOf[PipelineModel].stages(1).asInstanceOf[PipelineModel].stages(0).asInstanceOf[T]
  }

  override def explainParams(): String = {
    val stages = Seq(
      $(stopwordsRemover), $(idf), $(lda), $(logisticRegression)
    )
    stages.map(_.explainParams()).mkString("\n")
  }

  private def cleanerPipeline(): Array[PipelineStage] = {
    Array(new CleanFeaturesTransformer())
  }

  private def tokenizePipeline(): Array[PipelineStage] = {
    val mcTokenizer = new MultiColumnPipeline()
        .setStage(new RegexTokenizer().setPattern("""[\p{Punct} ]"""))
        .setInputCols(questions(""))
        .setOutputCols(questions("all_tokens"))
    val mcStopwordsRemover = new MultiColumnPipeline()
        .setStage($(stopwordsRemover))
        .setInputCols(mcTokenizer.getOutputCols)
        .setOutputCols(questions("tokens"))
    Array(mcTokenizer, mcStopwordsRemover)
  }

  private def vectorizePipeline(): Array[PipelineStage] = {
    // For the record, count vectorizer params since default vocab size of 2^18 is close enough to our
    // estimated vocab size of 110k in the training data.
    // for t in $(cat train.csv  | rev | cut -d, -f2,3 | rev |tr ',"' " " |  tr -cd '[[:alnum:]] ._-' | tr '[:upper:]' '[:lower:]' ); do echo -e $t; done | sort | uniq | wc -l
    val mcTf = new MultiColumnPipeline()
      .setInputCols(questions("tokens"))
      .setOutputCols(questions("tf"))
      .setStage($(countVectorizer))
    val mcIdf = new MultiColumnPipeline()
      .setInputCols(questions("tf"))
      .setOutputCols(questions("tfidf"))
      .setStage($(idf))
    Array(mcTf, mcIdf)
  }

 private def ldaPipeline(): Array[PipelineStage] = {
   // The "em" optimizer is distributed, supports serialization, but is disk hungry and slow.
   // The "online" runs in the driver, is fast but cannot be serialized.
   // We use the latter, since this model is only used to create a submission and nothing else.
   val optimizer = "online"
   val ldaEstimator = $(lda)
     .setOptimizer(optimizer)
     .setFeaturesCol("tmpinput").setTopicDistributionCol("tmpoutput")
   val mcLda = new MultiColumnPipeline()
     .setInputCols(questions("tfidf"))
     .setOutputCols(questions("lda"))
     .setStage(ldaEstimator, ldaEstimator.getFeaturesCol, ldaEstimator.getTopicDistributionCol)
   Array(mcLda)
  }

  private def questions(suffix: String) = questionsCols.map(_ + suffix)

  private def logisticRegressionPipeline(): Array[PipelineStage] = {
    val labelCol = "isDuplicateLabel"
    val assembler = new VectorAssembler().setInputCols(questions("lda")).setOutputCol("mergedlda")
    val labeler = new SQLTransformer().setStatement(
      s"SELECT *, cast(isDuplicate as int) $labelCol from __THIS__")
    // See https://www.kaggle.com/davidthaler/how-many-1-s-are-in-the-public-lb
    val weight = new SQLTransformer().setStatement(
      s"SELECT *, IF(isDuplicate, 0.47, 1.3) lrw from __THIS__")
    val lr = $(logisticRegression)
        .setFeaturesCol("mergedlda").setProbabilityCol("p").setRawPredictionCol("raw")
        .setWeightCol("lrw")
        .setLabelCol(labelCol)
    Array(assembler, labeler, weight, lr)
  }

  def copy(extra: ParamMap): QuoraQuestionsPairsPipeline = defaultCopy(extra)

  def setStopwordsRemover(value: StopWordsRemover): this.type = set(stopwordsRemover, value)

  def setIDF(value: IDF): this.type = set(idf, value)

  def setLDA(value: LDA): this.type = set(lda, value)

  def setLogisticRegression(value: LogisticRegression): this.type = set(logisticRegression, value)
}
