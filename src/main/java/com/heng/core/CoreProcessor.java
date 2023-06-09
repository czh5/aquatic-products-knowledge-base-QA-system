package com.heng.core;

import java.io.*;
import java.util.*;

import ai.catboost.spark.CatBoostClassificationModel;
import ai.catboost.spark.CatBoostClassifier;
import org.apache.spark.SparkConf;
import org.apache.spark.ml.classification.DecisionTreeClassificationModel;
import org.apache.spark.ml.classification.DecisionTreeClassifier;
import org.apache.spark.ml.feature.LabeledPoint;
import org.apache.spark.ml.classification.NaiveBayes;
import org.apache.spark.ml.classification.NaiveBayesModel;
import org.apache.spark.ml.linalg.Vector;
import org.apache.spark.ml.linalg.Vectors;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.Segment;
import com.hankcs.hanlp.seg.common.Term;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import scala.Tuple2;

/**
 * Spark贝叶斯分类器 + HanLP分词器 + 实现问题语句的抽象+模板匹配+关键性语句还原
 */
public class CoreProcessor {

    /**指定问题question及字典的txt模板所在的根目录*/
    private String rootDirPath;

    /**Spark分类器*/
    private NaiveBayesModel nb_model;           //朴素贝叶斯
    private DecisionTreeClassificationModel dt_model;         //决策树
    //private CatBoostClassificationModel cat_model;  //CatBoost

    /**分类标签号和问句模板对应表*/
    private Map<Double, String> questionsPattern;

    /**词语和下标的对应表   == 词汇表*/
    private Map<String, Integer> vocabulary;

    /**关键字与其词性的map键值对集合 == 句子抽象*/
    private Map<String, String> abstractMap;

    /** 分类模板索引*/
    int modelIndex = 0;

    /** 模板的数量*/
    private int modelNum = 26;  //也就是准备了多少个模板

    public CoreProcessor(String rootDirPath) throws Exception{
        this.rootDirPath = rootDirPath+'/';
        // 加载问题模板
        questionsPattern = loadQuestionTemplates();
        // 加载词汇表
        vocabulary = loadVocabulary();
        //加载样本集
        List<LabeledPoint> sample_list = getSamplesList();
        // 加载分类模型，初始化分类器对象
        Tuple2<NaiveBayesModel, Double> nb = loadNaiveBayesClassifierModel(sample_list);
        Tuple2<DecisionTreeClassificationModel, Double> dt = loadDecisionTreeClassifierModel(sample_list);
        //Tuple2<CatBoostClassificationModel, Double> cat = loadCatBoostClassificationModel(sample_list);
        nb_model = nb._1;
        dt_model = dt._1;
        //cat_model = cat._1;

        System.out.println("朴素贝叶斯分类器的准确率 == " + nb._2);
        System.out.println("决策树分类器的准确率 == " + dt._2);
        //System.out.println("CatBoost分类器的准确率 == " + cat._2);
    }

    /**
     * 问句拆解，套用模板，得到关键word，核心方法实现
     * @param querySentence 问句
     * @return 结果集合（问题模板索引、关键word数组）
     * @throws Exception
     */
    public List<String> analysis(String querySentence) throws Exception {

        /**原始问句*/
        System.out.println("原始句子："+querySentence);
        System.out.println("========HanLP开始分词========");

        /**抽象句子，利用HanPL分词，将关键字进行词性抽象*/
        String abstractStr = queryAbstract(querySentence);
        System.out.println("句子抽象化结果："+abstractStr);

        /**将抽象的句子与Spark训练集中的模板进行匹配，拿到句子对应的模板*/
        String strPattern = queryClassify(abstractStr);
        System.out.println("句子套用模板结果："+strPattern);

        /**模板还原成句子，此时问题已转换为熟悉的操作*/
        String finalPattern = sentenceReduction(strPattern);
        System.out.println("原始句子替换成系统可识别的结果："+finalPattern);

        List<String> resultList = new ArrayList<>();
        resultList.add(String.valueOf(modelIndex));
        String[] finalPatternArr = finalPattern.split(" ");
        for (String word : finalPatternArr)
            resultList.add(word);
        return resultList;

    }

    /**
     * 将HanLp分词后的关键word，用抽象词性xx替换
     * @param querySentence 查询句子
     * @return
     */
    public  String queryAbstract(String querySentence) {

        // 句子抽象化
        Segment segment = HanLP.newSegment().enableCustomDictionary(true);
        List<Term> terms = segment.seg(querySentence);
        String abstractQuery = "";
        abstractMap = new HashMap<>();

        for (Term term : terms) {
            String word = term.word;
            String termStr = term.toString();
            System.out.println(termStr);
            //如果抽象语句中包含了 水产品幼体、饵料、疾病、设备、栖息地、场地、水产品 ，则加入抽象表中
            if (termStr.contains("napl")) {          //水产品幼体
                abstractQuery += "napl ";
                abstractMap.put("napl", word);
            } else if (termStr.contains("nba")) {   //饵料
                abstractQuery += "nba ";
                abstractMap.put("nba", word);
            } else if (termStr.contains("ndi")) {   //疾病
                abstractQuery += "ndi ";
                abstractMap.put("ndi", word);
            } else if (termStr.contains("neq")) {   //设备
                abstractQuery += "neq ";
                abstractMap.put("neq", word);
            } else if (termStr.contains("nha")) {   //栖息地
                abstractQuery += "nha ";
                abstractMap.put("nha", word);
            } else if (termStr.contains("nve")) {   //场地
                abstractQuery += "nve ";
                abstractMap.put("nve", word);
            } else if (termStr.contains("nap")) {  //水产品
                abstractQuery += "nap ";
                abstractMap.put("nap", word);
            } else {
                abstractQuery += word + " ";
            }

        }

        System.out.println("========HanLP分词结束========");
        return abstractQuery;
    }

    /**
     * 将句子模板还原成正常的语句（分词关键word的抽象词性替换成原有的word）
     * @param queryPattern
     * @return
     */
    public String sentenceReduction(String queryPattern) {
        Set<String> set = abstractMap.keySet();
        for (String key : set) {
            /**如果句子模板中含有抽象的词性*/
            if (queryPattern.contains(key)) {
                /**则替换抽象词性为具体的值*/
                String value = abstractMap.get(key);
                queryPattern = queryPattern.replace(key, value);
            }
        }
        String extendedQuery = queryPattern;
        /**当前句子处理完，抽象map清空释放空间并置空，等待下一个句子的处理*/
        abstractMap.clear();
        abstractMap = null;
        return extendedQuery;
    }


    /**
     * 加载词汇表 == 关键特征 == 与HanLP分词后的单词进行匹配
     * @return
     */
    public Map<String,Integer> loadVocabulary() {
        Map<String, Integer> vocabulary = new HashMap<String, Integer>();
        File file = new File(rootDirPath + "question/vocabulary.txt");
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        String line;
        try {
            while ((line = br.readLine()) != null) {
                //词汇表文件中内容的格式是 编号:词汇
                String[] tokens = line.split(":");
                int index = Integer.parseInt(tokens[0].replace("\uFEFF",""));
                String word = tokens[1];
                vocabulary.put(word, index);
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return vocabulary;
    }

    /**
     * 读取问题模板，并将其转化为LabeledPoint格式
     * @return
     * @throws Exception
     */
    public List<LabeledPoint> getSamplesList() throws Exception{
        List<LabeledPoint> sample_list = new LinkedList<>();
        String[] sentences;
        Map<Integer, String> seqWithSamples = loadQuestionSamples("question/question_samples.txt");
        if(seqWithSamples == null || seqWithSamples.size() == 0){
            throw new Exception("缺少问题训练样本，请核查！");
        }

        for (Map.Entry<Integer, String> entry : seqWithSamples.entrySet()) {
            //得到问题编号
            Integer seq = entry.getKey();
            String sampleContent = entry.getValue();
            //得到该问题的所有问法
            sentences = sampleContent.split("`");
            for (String sentence : sentences) {
                double[] array = sentenceToArrays(sentence);
                LabeledPoint sample = new LabeledPoint(seq, Vectors.dense(array));
                sample_list.add(sample);
            }
        }
        return sample_list;
    }

    /**
     * 句子分词后与词汇表进行key匹配转换为double向量数组
     * @param sentence 句子
     * @return 向量数组
     */
    public double[] sentenceToArrays(String sentence){
        double[] vector = new double[vocabulary.size()];
        /**模板对照词汇表的大小进行初始化，全部为0.0*/
        for (int i = 0; i < vocabulary.size(); i++) {
            vector[i] = 0;
        }

        /** HanLP分词，拿分词的结果和词汇表里面的关键特征进行匹配*/
        Segment segment = HanLP.newSegment();
        List<Term> terms = segment.seg(sentence);
        for (Term term : terms) {
            String word = term.word;
            /**如果命中，0.0 改为 1.0*/
            if (vocabulary.containsKey(word)) {
                int index = vocabulary.get(word);
                vector[index] = 1;
            }
        }
        return vector;
    }

    /**
     * Spark朴素贝叶斯(naiveBayes)
     * 对特定的模板进行加载并分类
     * @return
     * @throws Exception
     */
    public Tuple2<NaiveBayesModel, Double> loadNaiveBayesClassifierModel(List<LabeledPoint> sample_list) throws Exception {

        // 创建SparkSession
        SparkConf conf = new SparkConf().setAppName("NaiveBayesModel").setMaster("local[*]");
        SparkSession spark = SparkSession.builder().config(conf).getOrCreate();

        // 将JavaRDD转换为Dataset
        Dataset<Row> dataset = spark.createDataFrame(sample_list, LabeledPoint.class);
        // 划分数据集
        Dataset<Row>[] splits = dataset.randomSplit(new double[]{0.8, 0.2});
        Dataset<Row> trainData = splits[0];
        Dataset<Row> testData = splits[1];

        /**开始训练样本*/
        NaiveBayesModel nb_model = new NaiveBayes().fit(trainData);

        /**模型准确率*/
        // 使用分类器进行预测
        Dataset<Row> predictions = nb_model.transform(testData).select("prediction");
        // 计算准确率
        double accuracy = predictions.filter("prediction=label").count() / (double) testData.count();

        /** 关闭资源*/
        spark.close();
        /** 返回分类器*/
        return new Tuple2<>(nb_model, accuracy);
    }

    /**
     * Spark决策树(DecisionTree)
     * 对特定的模板进行加载并分类
     * @return
     * @throws Exception
     */
    public Tuple2<DecisionTreeClassificationModel, Double> loadDecisionTreeClassifierModel(List<LabeledPoint> sample_list) throws Exception {

        // 创建SparkSession
        SparkConf conf = new SparkConf().setAppName("DecisionTreeModel").setMaster("local[*]");
        SparkSession spark = SparkSession.builder().config(conf).getOrCreate();

        // 将JavaRDD转换为Dataset
        Dataset<Row> dataset = spark.createDataFrame(sample_list, LabeledPoint.class);
        // 划分数据集
        Dataset<Row>[] splits = dataset.randomSplit(new double[]{0.8, 0.2});
        Dataset<Row> trainData = splits[0];
        Dataset<Row> testData = splits[1];


        /**开始训练样本*/
        // 设置决策树参数
        int numClasses = modelNum;    //问题模板的数量
        String impurity = "entropy";   //不纯度，gini或者entropy
        int maxDepth = 16;      //树的最大深度
        int maxBins = 32;       //最大划分数
        // 训练决策树模型
        DecisionTreeClassifier classifier =
                new DecisionTreeClassifier().setMaxBins(maxBins).setMaxDepth(maxDepth).setImpurity(impurity);
        DecisionTreeClassificationModel dt_model = classifier.fit(trainData);
        // 使用分类器进行预测
        Dataset<Row> predictions = dt_model.transform(testData).select("prediction");
        // 计算准确率
        double accuracy = predictions.filter("prediction=label").count() / (double) testData.count();

        /** 关闭资源*/
        spark.close();
        /** 返回分类器*/
        return new Tuple2<>(dt_model, accuracy);
    }

    /**
     * CatBoost分类器
     * */
    public Tuple2<CatBoostClassificationModel, Double> loadCatBoostClassificationModel(List<LabeledPoint> sample_list) {
        // 创建SparkSession
        SparkConf conf = new SparkConf().setAppName("CatBoostClassificationModel").setMaster("local[*]");
        SparkSession spark = SparkSession.builder().config(conf).getOrCreate();

        // 将JavaRDD转换为Dataset
        Dataset<Row> dataset = spark.createDataFrame(sample_list, LabeledPoint.class);
        // 划分数据集
        Dataset<Row>[] splits = dataset.randomSplit(new double[]{0.8, 0.2});
        Dataset<Row> trainData = splits[0];
        Dataset<Row> testData = splits[1];
        // 定义分类器
        CatBoostClassifier classifier = new CatBoostClassifier();
        classifier.setIterations(10);
        classifier.setLearningRate(0.1f);
        classifier.setDepth(5);
        classifier.setLossFunction("MultiClass");
        // 训练分类器
        CatBoostClassificationModel model = classifier.fit(trainData);
        // 使用分类器进行预测
        Dataset<Row> predictions = model.transform(testData).select("prediction");
        // 计算准确率
        double accuracy = predictions.filter("prediction=label").count() / (double) testData.count();
        // 关闭SparkSession
        spark.close();
        // 返回模型和准确率
        return new Tuple2<>(model, accuracy);
    }

    /**
     * 加载问题样本数据，并返回每个样本的序号和内容键值对
     * @param path 路径
     * @return Map<Integer,String>
     */
    public Map<Integer,String> loadQuestionSamples(String path) throws IOException {

        File file = new File(rootDirPath+path);
        if(!file.exists()){
            throw new IOException("文件不存在！");
        }

        Map<Integer,String> seqWithSamples = new HashMap<>(16);
        BufferedReader br = new BufferedReader(new FileReader(file));
        Integer seqStr = null;   //模板编号
        String content = "";
        String line;
        while ((line = br.readLine()) != null) {
            if(line.equals("")) {
                continue;
            }
            /**模板对应的具体问法，以【===== 编号 模板 =====】 这样的一行开始*/
            if(line.startsWith("=====")) {
                if (seqStr != null) {
                    seqWithSamples.put(seqStr, content);
                    content = "";
                }
                seqStr = Integer.parseInt(line.split(" ")[1]);
                continue;
            }
            /**文本的换行符用"`"代替，content保存了一个模板对应的所有问法*/
            content += line + "`";
        }
        /**退出循环后content还保存着最后一个模板对应的所有问法，因此要加进去*/
        seqWithSamples.put(seqStr, content);

        return seqWithSamples;
    }

    /**
     * 加载问题模板 == 分类器标签
     * @return Map<Double, String> == 序号，问题分类
     */
    public Map<Double,String> loadQuestionTemplates() {
        Map<Double, String> questionsPattern = new HashMap<>(16);
        File file = new File(rootDirPath + "question/question_classification.txt");
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
        } catch (FileNotFoundException e1) {
            e1.printStackTrace();
        }
        String line;
        try {
            while ((line = br.readLine()) != null) {
                //问题模板文件每一行的内容为 编号:模板
                String[] tokens = line.split(":");
                double index = Double.valueOf(tokens[0].replace("\uFEFF",""));
                String pattern = tokens[1];
                questionsPattern.put(index, pattern);
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return questionsPattern;
    }

    /**
     * 分类器分类的结果，拿到匹配的分类标签号，后续可根据标签号定位到指定的问题模板
     * @param sentence 句子
     * @return
     * @throws Exception
     */
    public String queryClassify(String sentence) throws Exception {

        double[] testArray = sentenceToArrays(sentence);
        Vector v = Vectors.dense(testArray);
        /**
         * 对数据进行预测predict
         * 句子模板在 spark贝叶斯分类器中的索引【位置】
         * 根据词汇使用的频率推断出句子对应哪一个模板
         * 原则：高频率的会被预测出
         */
        //分别使用朴素贝叶斯分类器和决策树分类器预测
        double index = nb_model.predict(v);
        double index2 = dt_model.predict(v);
        //此处可以选择准确率最高的那个分类器，为方便选择nb_model
        modelIndex = (int)index;

        System.out.println("朴素贝叶斯分类器预测该问题属于的模板编号 == " + index);
        System.out.println("决策树分类器预测该问题属于的模板编号 ===== " + index);

        double[] probabilities = nb_model.predictProbability(v).toArray();
        double[] probabilities2 = dt_model.predictProbability(v).toArray();
        System.out.println("============ 问题模板分类概率 =============");
        for (int i = 0; i < probabilities.length; i++) {
            System.out.println("问题模板分类【"+i+"】概率：" +
                    String.format("%.5f", probabilities[i]) + "(nb)");
        }
        System.out.println("============ 问题模板分类概率 =============");


        /**这里返回index还是index2则表明选用哪个分类器*/
        return questionsPattern.get(index);

    }
}
