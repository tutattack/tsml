/*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package tsml.classifiers.interval_based;

import evaluation.evaluators.CrossValidationEvaluator;
import evaluation.storage.ClassifierResults;
import evaluation.tuning.ParameterSpace;
import experiments.data.DatasetLoading;
import fileIO.OutFile;
import machine_learning.classifiers.TimeSeriesTree;
import tsml.classifiers.*;
import tsml.data_containers.TSCapabilities;
import tsml.data_containers.TimeSeriesInstance;
import tsml.data_containers.TimeSeriesInstances;
import tsml.data_containers.utilities.Converter;
import tsml.transformers.Catch22;
import tsml.transformers.ColumnNormalizer;
import tsml.transformers.Differences;
import tsml.transformers.PowerSpectrum;
import utilities.ClassifierTools;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.core.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.*;
import java.util.function.Function;

import static utilities.ArrayUtilities.sum;
import static utilities.StatisticalUtilities.median;
import static utilities.Utilities.argMax;

/**
 * Implementation of the catch22 Interval Forest algorithm
 *
 * @author Matthew Middlehurst
 **/
public class SCIF extends EnhancedAbstractClassifier implements TechnicalInformationHandler, TrainTimeContractable,
        Checkpointable, Tuneable, MultiThreadable, Visualisable, Interpretable {


    public boolean supervisedIntervals = true;
    public boolean extraDims = true;
    public boolean classBalancing = true;

    /**
     * Paper defining CIF.
     *
     * @return TechnicalInformation for CIF
     */
    @Override //TechnicalInformationHandler
    public TechnicalInformation getTechnicalInformation() {
        //TODO update
//        TechnicalInformation result;
//        result = new TechnicalInformation(TechnicalInformation.Type.ARTICLE);
//        result.setValue(TechnicalInformation.Field.AUTHOR, "M. Middlehurst, J. Large and A. Bagnall");
//        result.setValue(TechnicalInformation.Field.TITLE, "The Canonical Interval Forest (CIF) Classifier for " +
//                "Time Series Classifciation");
//        result.setValue(TechnicalInformation.Field.YEAR, "2020");
//        return result;
        return null;
    }

    /** Primary parameters potentially tunable */
    private int numClassifiers = 500;

    /** Amount of attributes to be subsampled and related data storage. */
    private int attSubsampleSize = 10;
    private int numAttributes = 29;
    private int startNumAttributes;
    private ArrayList<ArrayList<Integer>> subsampleAtts;

    /** Normalise outlier catch22 features which break on data not normalised */
    private boolean outlierNorm = true;

    /** Use STSF features as well as catch22 features */
    private boolean useSummaryStats = true;

    /** Ensemble members of base classifier, default to TimeSeriesTree */
    private ArrayList<Classifier> trees;
    private Classifier base= new TimeSeriesTree();

    /** Attributes used in each tree, used to skip transforms for unused attributes/intervals **/
    private ArrayList<boolean[]> attUsage;

    /** for each classifier i representation r attribute a interval j  starts at intervals[i][r][a][j][0] and
     ends  at  intervals[i][r][a][j][1] */
    private ArrayList<ArrayList<int[]>[][]> intervals;

    /**Holding variable for test classification in order to retain the header info*/
    private ArrayList<Instances> testHolders;

    /** Flags and data required if Bagging **/
    public boolean bagging = true;
    private int[] oobCounts;
    private double[][] trainDistributions;

    /** Flags and data required if Checkpointing **/
    private boolean checkpoint = false;
    private String checkpointPath;
    private long checkpointTime = 0;
    private long lastCheckpointTime = 0;
    private long checkpointTimeDiff = 0;
    private boolean internalContractCheckpointHandling = true;

    /** Flags and data required if Contracting **/
    private boolean trainTimeContract = false;
    private long contractTime = 0;
    private int maxClassifiers = 500;

    /** Multithreading **/
    private int numThreads = 1;
    private boolean multiThread = false;
    private ExecutorService ex;

    /** Visualisation and interpretability **/
    private String visSavePath;
    private int visNumTopAtts = 3;
    private String interpSavePath;
    private ArrayList<ArrayList<double[]>> interpData;
    private ArrayList<Integer> interpTreePreds;
    private int interpCount = 0;
    private double[] interpSeries;
    private int interpPred;

    /** data information **/
    private int seriesLength;
    private int numInstances;
    private int newNumInstances;

    /** Multivariate **/
    private int numDimensions;
    private ArrayList<ArrayList<Integer>> intervalDimensions;

    /** Transformer used to obtain catch22 features **/
    private transient Catch22 c22;

    private PowerSpectrum ps = new PowerSpectrum();
    private Differences di = new Differences();

    protected static final long serialVersionUID = 1L;

    /**
     * Default constructor for CIF. Can estimate own performance.
     */
    public SCIF(){
        super(CAN_ESTIMATE_OWN_PERFORMANCE);
    }

    /**
     * Set the number of trees to be built.
     *
     * @param t number of trees
     */
    public void setNumTrees(int t){
        numClassifiers = t;
    }

    /**
     * Set the number of attributes to be subsampled per tree.
     *
     * @param a number of attributes sumsampled
     */
    public void setAttSubsampleSize(int a) {
        attSubsampleSize = a;
    }

    /**
     * Set whether to use the original TSF statistics as well as catch22 features.
     *
     * @param b boolean to use summary stats
     */
    public void setUseSummaryStats(boolean b) {
        useSummaryStats = b;
    }

    /**
     * Set whether to normalise the outlier catch22 features.
     *
     * @param b boolean to set outlier normalisation
     */
    public void setOutlierNorm(boolean b) {
        outlierNorm = b;
    }

    /**
     * Sets the base classifier for the ensemble.
     *
     * @param c a base classifier constructed elsewhere and cloned into ensemble
     */
    public void setBaseClassifier(Classifier c){
        base=c;
    }

    /**
     * Set whether to perform bagging with replacement.
     *
     * @param b boolean to set bagging
     */
    public void setBagging(boolean b){
        bagging = b;
    }

    /**
     * Set the number of attributes to show when creating visualisations.
     *
     * @param i number of attributes
     */
    public void setVisNumTopAtts(int i){
        visNumTopAtts = i;
    }

    /**
     * Outputs CIF parameters information as a String.
     *
     * @return String written to results files
     */
    @Override //SaveParameterInfo
    public String getParameters() {
        int nt = numClassifiers;
        if (trees != null) nt = trees.size();
        String temp=super.getParameters()+",numTrees,"+nt+",attSubsampleSize,"+attSubsampleSize+
                ",outlierNorm,"+outlierNorm+",basicSummaryStats,"+useSummaryStats+
                ",baseClassifier,"+base.getClass().getSimpleName()+",bagging,"+bagging+
                ",estimator,"+estimator.name()+",contractTime,"+contractTime;
        return temp;
    }

    /**
     * Returns the capabilities for CIF. These are that the
     * data must be numeric or relational, with no missing and a nominal class
     *
     * @return the capabilities of CIF
     */
    @Override //AbstractClassifier
    public Capabilities getCapabilities(){
        Capabilities result = super.getCapabilities();
        result.disableAll();

        result.setMinimumNumberInstances(2);

        // attributes
        result.enable(Capabilities.Capability.RELATIONAL_ATTRIBUTES);
        result.enable(Capabilities.Capability.NUMERIC_ATTRIBUTES);

        // class
        result.enable(Capabilities.Capability.NOMINAL_CLASS);

        return result;
    }

    /**
     * Returns the time series capabilities for CIF. These are that the
     * data must be equal length, with no missing values
     *
     * @return the time series capabilities of CIF
     */
    public TSCapabilities getTSCapabilities(){
        TSCapabilities capabilities = new TSCapabilities();
        capabilities.enable(TSCapabilities.EQUAL_LENGTH)
                .enable(TSCapabilities.MULTI_OR_UNIVARIATE)
                .enable(TSCapabilities.NO_MISSING_VALUES);
        return capabilities;
    }

    /**
     * Build the CIF classifier.
     *
     * @param data TimeSeriesInstances object
     * @throws Exception unable to train model
     */
    @Override //TSClassifier
    public void buildClassifier(TimeSeriesInstances data) throws Exception {
        /** Build Stage:
         *  Builds the final classifier with or without bagging.
         */
        //require last class idx

        trainResults = new ClassifierResults();
        rand.setSeed(seed);
        numClasses = data.numClasses();
        trainResults.setClassifierName(getClassifierName());
        trainResults.setBuildTime(System.nanoTime());
        // can classifier handle the data?
        getTSCapabilities().test(data);

        File file = new File(checkpointPath + "CIF" + seed + ".ser");
        //if checkpointing and serialised files exist load said files
        if (checkpoint && file.exists()){
            //path checkpoint files will be saved to
            if(debug)
                System.out.println("Loading from checkpoint file");
            loadFromFile(checkpointPath + "CIF" + seed + ".ser");
        }
        //initialise variables
        else {
            seriesLength = data.getMaxLength();
            numInstances = data.numInstances();
            numDimensions = data.getMaxNumChannels();

            if (!useSummaryStats){
                numAttributes = 22;
            }

            startNumAttributes = numAttributes;
            subsampleAtts = new ArrayList<>();

            if (attSubsampleSize < numAttributes) {
                numAttributes = attSubsampleSize;
            }

            //Set up for Bagging if required
            if(bagging && getEstimateOwnPerformance()) {
                trainDistributions = new double[numInstances][numClasses];
                oobCounts = new int[numInstances];
            }

            //cancel loop using time instead of number built.
            if (trainTimeContract){
                numClassifiers = maxClassifiers;
                trees = new ArrayList<>();
                intervals = new ArrayList<>();
                attUsage = new ArrayList<>();
            }
            else{
                trees = new ArrayList<>(numClassifiers);
                intervals = new ArrayList<>(numClassifiers);
                attUsage = new ArrayList<>(numClassifiers);
            }

            intervalDimensions = new ArrayList<>();
            testHolders = new ArrayList<>();
        }

        if (multiThread) {
            ex = Executors.newFixedThreadPool(numThreads);
            if (checkpoint) System.out.println("Unable to checkpoint until end of build when multi threading.");
        }

        c22 = new Catch22();
        c22.setOutlierNormalise(outlierNorm);

        ArrayList<Integer>[] idxByClass = new ArrayList[data.numClasses()];
        for (int i = 0; i < idxByClass.length; i++){
            idxByClass[i] = new ArrayList<>();
        }
        for (int i = 0; i < data.numInstances(); i++){
            idxByClass[data.get(i).getLabelIndex()].add(i);
        }

        double average = (double)data.numInstances()/data.numClasses();
        int[] instToAdd = new int[numInstances];
        int[] classCounts = new int[data.numClasses()];
        for (int i = 0; i < idxByClass.length; i++) {
            if (classBalancing && idxByClass[i].size() < average) {
                int n = idxByClass[i].size();
                while (n < average) {
                    instToAdd[idxByClass[i].get(rand.nextInt(idxByClass[i].size()))]++;
                    n++;
                }
                classCounts[i] = n;
            }
            else{
                classCounts[i] = idxByClass[i].size();
            }
        }

        if (classBalancing) newNumInstances = numInstances + sum(instToAdd);
        else newNumInstances = numInstances;

        TimeSeriesInstances[] representations;
        if (extraDims) {
            representations = new TimeSeriesInstances[3];
            representations[0] = data;
            ps = new PowerSpectrum();
            representations[1] = ps.transform(representations[0]);
            di = new Differences();
            di.setSubtractFormerValue(true);
            representations[2] = di.transform(representations[0]);
        }
        else{
            representations = new TimeSeriesInstances[1];
            representations[0] = data;
        }

//        h = new Hilbert();
//        representations[3] = h.transform(representations[0]);
//        ar = new ARMA();
//        ar.setMaxLag(Math.max((data.numAttributes() - 1) / 4, 10));
//        representations[4] = ar.transform(representations[0]);
//        acf = new ACF();
//        acf.setMaxLag(Math.max((data.numAttributes() - 1) / 4, 10));
//        representations[5] = acf.transform(representations[0]);
//        mp = new MatrixProfile(Math.max((data.numAttributes() - 1) / 4, 10));
//        representations[6] = mp.transform(representations[0]);

        if (multiThread){
            //multiThreadBuildCIF(data);
        }
        else{
            buildCIF(representations, instToAdd, classCounts);
        }

        if(trees.size() == 0){//Not enough time to build a single classifier
            throw new Exception((" ERROR in CIF, no trees built, contract time probably too low. Contract time = "
                    + contractTime));
        }

        if(checkpoint) {
            saveToFile(checkpointPath);
        }

        trainResults.setTimeUnit(TimeUnit.NANOSECONDS);
        trainResults.setBuildTime(System.nanoTime() - trainResults.getBuildTime() - checkpointTimeDiff
                - trainResults.getErrorEstimateTime());

        if(getEstimateOwnPerformance()){
            long est1 = System.nanoTime();
            estimateOwnPerformance(data);
            long est2 = System.nanoTime();
            trainResults.setErrorEstimateTime(est2 - est1 + trainResults.getErrorEstimateTime());
        }
        trainResults.setBuildPlusEstimateTime(trainResults.getBuildTime() + trainResults.getErrorEstimateTime());
        trainResults.setParas(getParameters());
        printLineDebug("*************** Finished CIF Build with " + trees.size() + " Trees built in " +
                trainResults.getBuildTime()/1000000000 + " Seconds  ***************");
    }

    /**
     * Build the CIF classifier.
     *
     * @param data weka Instances object
     * @throws Exception unable to train model
     */
    @Override //AbstractClassifier
    public void buildClassifier(Instances data) throws Exception {
        buildClassifier(Converter.fromArff(data));
    }

    /**
     * Build the CIF classifier
     * For each base classifier
     *     generate random intervals
     *     do the transfrorms
     *     build the classifier
     *
     * @throws Exception unable to build CIF
     */
    public void buildCIF(TimeSeriesInstances[] representations, int[] instToAdd, int[] classCounts)
            throws Exception {
        while(withinTrainContract(trainResults.getBuildTime()) && trees.size() < numClassifiers) {
            int i = trees.size();

            //If bagging find instances with replacement
            int[] instInclusions = null;
            boolean[] inBag = null;
            int[] baggingClassCounts = classCounts;
            if (bagging || classBalancing) {
                inBag = new boolean[numInstances];
                instInclusions = new int[numInstances];

                for (int n = 0; n < numInstances; n++) {
                    instInclusions[rand.nextInt(numInstances)]++;
                    if (classBalancing) instInclusions[n] += instToAdd[n];
                }

                baggingClassCounts = new int[numClasses];
                for (int n = 0; n < numInstances; n++) {
                    if (instInclusions[n] > 0) {
                        inBag[n] = true;
                        baggingClassCounts[representations[0].get(n).getLabelIndex()] += instInclusions[n];
                    }
                }
            }

            //find attributes to subsample
            subsampleAtts.add(new ArrayList<>());
            for (int n = 0; n < startNumAttributes; n++){
                subsampleAtts.get(i).add(n);
            }

            while (subsampleAtts.get(i).size() > numAttributes){
                subsampleAtts.get(i).remove(rand.nextInt(subsampleAtts.get(i).size()));
            }

            //1. Select random intervals for tree i
            int totalAtts = 0;
            if (supervisedIntervals) {
                intervals.add(new ArrayList[3][]);
                for (int r = 0; r < representations.length; r++) {
                    intervals.get(i)[r] = findCandidateDiscriminatoryIntervals(representations[r], instInclusions,
                            baggingClassCounts, subsampleAtts.get(i));

                    for (int a = 0; a < intervals.get(i)[r].length; a++) {
                        totalAtts += intervals.get(i)[r][a].size();
                    }
                }
            }
            else {
                intervals.add(new ArrayList[representations.length][]);
                int minIntervalLength = 3;
                int numIntervals = 4;
                for (int r = 0; r < representations.length; r++) {
                    ArrayList<int[]> intervals2 = new ArrayList<>();  //Start and end

                    for (int j = 0; j < numIntervals; j++) {
                        int[] interval = new int[2];
                        if (rand.nextBoolean()) {
                            interval[0] = rand.nextInt(representations[r].getMaxLength() - minIntervalLength); //Start point

                            int range = Math.min(representations[r].getMaxLength() - interval[0], representations[r].getMaxLength());
                            int length = rand.nextInt(range - minIntervalLength) + minIntervalLength;
                            interval[1] = interval[0] + length;
                        } else {
                            interval[1] = rand.nextInt(representations[r].getMaxLength() - minIntervalLength)
                                    + minIntervalLength; //Start point

                            int range = Math.min(interval[1], representations[r].getMaxLength());
                            int length;
                            if (range - minIntervalLength == 0) length = minIntervalLength;
                            else length = rand.nextInt(range - minIntervalLength) + minIntervalLength;
                            interval[0] = interval[1] - length;
                        }
                        intervals2.add(interval);
                    }

                    ArrayList<int[]>[] intervals3 = new ArrayList[numAttributes];
                    for (int n = 0; n < numAttributes; n++) {
                        intervals3[n] = intervals2;
                    }
                    intervals.get(i)[r] = intervals3;
                }
                totalAtts = numIntervals * representations.length * numAttributes;
            }

            //find dimensions for each interval
//            intervalDimensions.add(new ArrayList<>());
//            for (int n = 0; n < numIntervals; n++) {
//                intervalDimensions.get(i).add(rand.nextInt(numDimensions));
//            }
//            Collections.sort(intervalDimensions.get(i));

            //Set up instances size and format.
            ArrayList<Attribute> atts = new ArrayList<>();
            for (int j = 0; j < totalAtts; j++)
                atts.add(new Attribute("att" + j));
            //Get the class values as an array list
            ArrayList<String> vals = new ArrayList<>(numClasses);
            for(int j = 0; j < numClasses; j++)
                vals.add(Integer.toString(j));
            atts.add(new Attribute("cls", vals));
            //create blank instances with the correct class value
            Instances result = new Instances("Tree", atts, newNumInstances);
            result.setClassIndex(result.numAttributes() - 1);

            Instances testHolder = new Instances(result, 0);
            testHolder.add(new DenseInstance(result.numAttributes()));
            testHolders.add(testHolder);

            //For bagging
            int instIdx = 0;
            int lastIdx = -1;

            //2. Generate and store attributes
            for (int k = 0; k < newNumInstances; k++) {
                //For each instance
                if (bagging || classBalancing) {
                    boolean sameInst = false;

                    while (true) {
                        if (instInclusions[instIdx] == 0) {
                            instIdx++;
                        } else {
                            instInclusions[instIdx]--;

                            if (instIdx == lastIdx) {
                                result.add(k, new DenseInstance(result.instance(k - 1)));
                                sameInst = true;
                            } else {
                                lastIdx = instIdx;
                            }

                            break;
                        }
                    }

                    if (sameInst) continue;
                } else {
                    instIdx = k;
                }

                DenseInstance in = new DenseInstance(result.numAttributes());
                in.setValue(result.numAttributes()-1, representations[0].get(instIdx).getLabelIndex());
                result.add(in);

                int p = 0;
                for (int r = 0; r < representations.length; r++) {
                    double[] series = representations[r].get(instIdx).getHSliceArray(0); //todo mv

                    for (int a = 0; a < numAttributes; a++) {
                        for (int j = 0; j < intervals.get(i)[r][a].size(); j++) {
                            int[] interval = intervals.get(i)[r][a].get(j);
                            if (subsampleAtts.get(i).get(a) < 22){
                                double[] intervalArray = Arrays.copyOfRange(series, interval[0], interval[1] + 1);
                                result.instance(k).setValue(p,
                                        c22.getSummaryStatByIndex(subsampleAtts.get(i).get(a), j, intervalArray));
                            }
                            else {
                                result.instance(k).setValue(p,
                                        FeatureSet.calcFeatureByIndex(subsampleAtts.get(i).get(a), interval[0],
                                                interval[1], series));
                            }

                            p++;
                        }
                    }
                }
            }

            //3. Create and build tree using all the features. Feature selection
            Classifier tree = AbstractClassifier.makeCopy(base);
            if (seedClassifier && tree instanceof Randomizable)
                ((Randomizable) tree).setSeed(seed * (i + 1));

            tree.buildClassifier(result);

            if (base instanceof TimeSeriesTree) {
                attUsage.add(((TimeSeriesTree)tree).getAttributesUsed());
            }
            else{
                boolean[] b = new boolean[totalAtts];
                Arrays.fill(b,true);
                attUsage.add(b);
            }

            if (bagging && getEstimateOwnPerformance()) {
//                long t1 = System.nanoTime();
//                boolean[] usedAtts = attUsage.get(i);
//
//                for (int n = 0; n < numInstances; n++) {
//                    if (inBag[n])
//                        continue;
//
//                    for (int j = 0; j < numIntervals; j++) {
//                        double[] series = dimensions[n][intervalDimensions.get(i).get(j)];
//
//                        FeatureSet f = new FeatureSet();
//                        double[] intervalArray = Arrays.copyOfRange(series, interval[j][0], interval[j][1] + 1);
//
//                        for (int g = 0; g < numAttributes; g++) {
//                            if (!usedAtts[j * numAttributes + g]) {
//                                testHolder.instance(0).setValue(j * numAttributes + g, 0);
//                                continue;
//                            }
//
//                            if (subsampleAtts.get(i).get(g) < 22) {
//                                testHolder.instance(0).setValue(j * numAttributes + g,
//                                        c22.getSummaryStatByIndex(subsampleAtts.get(i).get(g), j, intervalArray));
//                            } else {
//                                if (!f.calculatedFeatures) {
//                                    f.setFeatures(series, interval[j][0], interval[j][1]);
//                                }
//                                switch (subsampleAtts.get(i).get(g)) {
//                                    case 22:
//                                        testHolder.instance(0).setValue(j * numAttributes + g, f.mean);
//                                        break;
//                                    case 23:
//                                        testHolder.instance(0).setValue(j * numAttributes + g, f.stDev);
//                                        break;
//                                    case 24:
//                                        testHolder.instance(0).setValue(j * numAttributes + g, f.slope);
//                                        break;
//                                    default:
//                                        throw new Exception("att subsample basic features broke");
//                                }
//                            }
//                        }
//                    }
//
//                    double[] newProbs = tree.distributionForInstance(testHolder.instance(0));
//                    oobCounts[n]++;
//                    for (int k = 0; k < newProbs.length; k++)
//                        trainDistributions[n][k] += newProbs[k];
//                }
//
//                trainResults.setErrorEstimateTime(trainResults.getErrorEstimateTime() + (System.nanoTime() - t1));
            }

            trees.add(tree);

            //Timed checkpointing if enabled, else checkpoint every 100 trees
            if(checkpoint && ((checkpointTime>0 && System.nanoTime()-lastCheckpointTime>checkpointTime)
                    || trees.size()%100 == 0)) {
                saveToFile(checkpointPath);
            }
        }
    }

    /**
     * Build the CIF classifier using multiple threads.
     * Unable to checkpoint until after the build process while using multiple threads.
     * For each base classifier
     *     generate random intervals
     *     do the transfrorms
     *     build the classifier
     *
     * @param data TimeSeriesInstances data
     * @param result Instances object formatted for transformed data
     * @throws Exception unable to build CIF
     */
    private void multiThreadBuildCIF(TimeSeriesInstances data, Instances result) throws Exception {
//        double[][][] dimensions = data.toValueArray();
//        int[] classVals = data.getClassIndexes();
//        int buildStep = trainTimeContract ? numThreads : numClassifiers;
//
//        while (withinTrainContract(trainResults.getBuildTime()) && trees.size() < numClassifiers) {
//            ArrayList<Future<MultiThreadBuildHolder>> futures = new ArrayList<>(buildStep);
//
//            int end = trees.size()+buildStep;
//            for (int i = trees.size(); i < end; ++i) {
//                Instances resultCopy = new Instances(result, numInstances);
//                for(int n = 0; n < numInstances; n++){
//                    DenseInstance in = new DenseInstance(result.numAttributes());
//                    in.setValue(result.numAttributes()-1, result.instance(n).classValue());
//                    resultCopy.add(in);
//                }
//
//                futures.add(ex.submit(new TreeBuildThread(i, dimensions, classVals, resultCopy)));
//            }
//
//            for (Future<MultiThreadBuildHolder> f : futures) {
//                MultiThreadBuildHolder h = f.get();
//                trees.add(h.tree);
//                intervals.add(h.interval);
//                subsampleAtts.add(h.subsampleAtts);
//                intervalDimensions.add(h.intervalDimensions);
//                attUsage.add(h.attUsage);
//
//                if (bagging && getEstimateOwnPerformance()){
//                    trainResults.setErrorEstimateTime(trainResults.getErrorEstimateTime() + h.errorTime);
//                    for (int n = 0; n < numInstances; n++) {
//                        oobCounts[n] += h.oobCounts[n];
//                        for (int k = 0; k < numClasses; k++)
//                            trainDistributions[n][k] += h.trainDistribution[n][k];
//                    }
//                }
//            }
//        }
    }

    private ArrayList<int[]>[] findCandidateDiscriminatoryIntervals(TimeSeriesInstances rep, int[] instInclusions,
                                                                    int[] classCounts, ArrayList<Integer> atts)
                                                                throws Exception {
        int splitPoint = rand.nextInt(rep.getMaxLength()-8)+4; //min 4, max serieslength-4

        ColumnNormalizer rn = new ColumnNormalizer();
        rn.fit(rep);
        rn.setNormMethod(ColumnNormalizer.NormType.STD_NORMAL);
        double[][] data = rn.transform(rep).getHSliceArray(0); //todo mv
        int[] labels = rep.getClassIndexes();

        ArrayList<int[]>[] newIntervals = new ArrayList[atts.size()];
        for (int i = 0; i < atts.size(); i++){
            newIntervals[i] = new ArrayList<>();
            supervisedIntervalSearch(data, instInclusions, labels, atts.get(i), newIntervals[i], classCounts, 0,
                    splitPoint);
            supervisedIntervalSearch(data, instInclusions, labels, atts.get(i), newIntervals[i], classCounts,
                    splitPoint+1, rep.getMaxLength()-1);
        }

        return newIntervals;
    }

    private void supervisedIntervalSearch(double[][] data, int[] instInclusions, int[] labels, int featureIdx,
                                          ArrayList<int[]> intervals, int[] classCount, int start, int end)
                                        throws Exception {
        int seriesLength = end-start;
        if (seriesLength < 4) return;
        int halfSeriesLength = seriesLength/2;

        double[] x1 = new double[newNumInstances];
        double[] x2 = new double[newNumInstances];
        int[] y = new int[newNumInstances];

        int e1 = start + halfSeriesLength;
        int e2 = start + halfSeriesLength + 1;
        int instIdx = 0;
        int lastIdx = -1;
        int[] instInclusionsCopy = null;
        if (bagging || classBalancing) instInclusionsCopy = Arrays.copyOf(instInclusions, instInclusions.length);
        for (int i = 0; i < newNumInstances; i++){
            if (bagging || classBalancing) {
                boolean sameInst = false;

                while (true) {
                    if (instInclusionsCopy[instIdx] == 0) {
                        instIdx++;
                    } else {
                        instInclusionsCopy[instIdx]--;

                        if (instIdx == lastIdx) {
                            x1[i] = x1[i-1];
                            x1[i] = x1[i-1];
                            y[i] = y[i-1];
                            sameInst = true;
                        } else {
                            lastIdx = instIdx;
                        }

                        break;
                    }
                }

                if (sameInst) continue;
            } else {
                instIdx = i;
            }

            if (featureIdx < 22){
                x1[i] = c22.getSummaryStatByIndex(featureIdx, i, Arrays.copyOfRange(data[instIdx], start, e1 + 1));
                x2[i] = c22.getSummaryStatByIndex(featureIdx, i, Arrays.copyOfRange(data[instIdx], e2, end + 1));
            }
            else {
                x1[i] = FeatureSet.calcFeatureByIndex(featureIdx, start, e1, data[instIdx]);
                x2[i] = FeatureSet.calcFeatureByIndex(featureIdx, e2, end, data[instIdx]);
            }
            y[i] = labels[instIdx];
        }

        double s1 = fisherScore(x1, y, classCount);
        double s2 = fisherScore(x2, y, classCount);

        if (s2 < s1){
            intervals.add(new int[]{start, e1});
            supervisedIntervalSearch(data, instInclusions, labels, featureIdx, intervals, classCount, start, e1);
        }
        else{
            intervals.add(new int[]{e2, end});
            supervisedIntervalSearch(data, instInclusions, labels, featureIdx, intervals, classCount, e2, end);
        }
    }

    private double fisherScore(double[] x, int[] y, int[] classCounts){
        double a = 0, b = 0;

        double xMean = 0;
        for (double aDouble : x) {
            xMean += aDouble;
        }
        xMean /= x.length;

        for (int i = 0; i < classCounts.length; i++){
            double xyMean = 0;
            for (int n = 0; n < x.length; n++){
                if (i == y[n]) {
                    xyMean += x[n];
                }
            }
            xyMean /= classCounts[i];

            double squareSum = 0;
            for (int n = 0; n < x.length; n++){
                if (i == y[n]) {
                    double temp = x[n] - xyMean;
                    squareSum += temp * temp;
                }
            }
            double xyStdev = classCounts[i]-1 == 0 ? 0 : Math.sqrt(squareSum/(classCounts[i]-1));

            a += classCounts[i]*Math.pow(xyMean-xMean, 2);
            b += classCounts[i]*Math.pow(xyStdev, 2);
        }

        return b == 0 ? 0 : a/b;
    }

    /**
     * Estimate accuracy stage: Three scenarios
     * 1. If we bagged the full build (bagging ==true), we estimate using the full build OOB.
     *    If we built on all data (bagging ==false) we estimate either:
     * 2. With a 10 fold CV.
     * 3. Build a bagged model simply to get the estimate.
     *
     * @param data TimeSeriesInstances to estimate with
     * @throws Exception unable to obtain estimate
     */
    private void estimateOwnPerformance(TimeSeriesInstances data) throws Exception {
        if(bagging){
            // Use bag data, counts normalised to probabilities
            double[] preds=new double[data.numInstances()];
            double[] actuals=new double[data.numInstances()];
            long[] predTimes=new long[data.numInstances()];//Dummy variable, need something
            for(int j=0;j<data.numInstances();j++){
                long predTime = System.nanoTime();
                for(int k=0;k<trainDistributions[j].length;k++)
                    trainDistributions[j][k] /= oobCounts[j];
                preds[j] = findIndexOfMax(trainDistributions[j], rand);
                actuals[j] = data.get(j).getLabelIndex();
                predTimes[j] = System.nanoTime()-predTime;
            }
            trainResults.addAllPredictions(actuals,preds, trainDistributions, predTimes, null);
            trainResults.setClassifierName("CIFBagging");
            trainResults.setDatasetName(data.getProblemName());
            trainResults.setSplit("train");
            trainResults.setFoldID(seed);
            trainResults.setErrorEstimateMethod("OOB");
            trainResults.finaliseResults(actuals);
        }
        //Either do a CV, or bag and get the estimates
        else if(estimator== EstimatorMethod.CV){
            /** Defaults to 10 or numInstances, whichever is smaller.
             * Interface TrainAccuracyEstimate
             * Could this be handled better? */
            int numFolds=Math.min(data.numInstances(), 10);
            CrossValidationEvaluator cv = new CrossValidationEvaluator();
            if (seedClassifier)
                cv.setSeed(seed*5);
            cv.setNumFolds(numFolds);
            SCIF cif=new SCIF();
            cif.copyParameters(this);
            if (seedClassifier)
                cif.setSeed(seed*100);
            cif.setEstimateOwnPerformance(false);
            long tt = trainResults.getBuildTime();
            trainResults=cv.evaluate(cif,Converter.toArff(data));
            trainResults.setClassifierName("CIFCV");
            trainResults.setErrorEstimateMethod("CV_"+numFolds);
        }
        else if(estimator== EstimatorMethod.OOB || estimator==EstimatorMethod.NONE){
            /** Build a single new TSF using Bagging, and extract the estimate from this
             */
            SCIF tsf=new SCIF();
            tsf.copyParameters(this);
            tsf.setSeed(seed);
            tsf.setEstimateOwnPerformance(true);
            tsf.bagging=true;
            tsf.buildClassifier(data);
            trainResults=tsf.trainResults;
            trainResults.setClassifierName("CIFOOB");
            trainResults.setErrorEstimateMethod("OOB");
        }
    }

    /**
     * Copy the parameters of a CIF object to this.
     *
     * @param other A CIF object
     */
    private void copyParameters(SCIF other){
        this.numClassifiers = other.numClassifiers;
        this.attSubsampleSize = other.attSubsampleSize;
        this.outlierNorm = other.outlierNorm;
        this.useSummaryStats = other.useSummaryStats;
        this.base = other.base;
        this.bagging = other.bagging;
        this.trainTimeContract = other.trainTimeContract;
        this.contractTime = other.contractTime;
    }

    /**
     * Find class probabilities of an instance using the trained model.
     *
     * @param ins TimeSeriesInstance object
     * @return array of doubles: probability of each class
     * @throws Exception failure to classify
     */
    @Override //TSClassifier
    public double[] distributionForInstance(TimeSeriesInstance ins) throws Exception {
        double[] d = new double[numClasses];

        if (interpSavePath != null){
            interpData = new ArrayList<>();
            interpTreePreds = new ArrayList<>();
        }

        double[][] representations;
        if (extraDims){
            representations = new double[3][]; //todo mv
            representations[0] = ins.getHSliceArray(0);
            representations[1] = ps.transform(ins).getHSliceArray(0);
            representations[2] = di.transform(ins).getHSliceArray(0);
        }
        else{
            representations = new double[1][]; //todo mv
            representations[0] = ins.getHSliceArray(0);
        }

        if (multiThread){
//            ArrayList<Future<MultiThreadPredictionHolder>> futures = new ArrayList<>(trees.size());
//
//            for (int i = 0; i < trees.size(); ++i) {
//                Instances testCopy = new Instances(testHolder, 1);
//                DenseInstance in = new DenseInstance(testHolder.numAttributes());
//                in.setValue(testHolder.numAttributes()-1, -1);
//                testCopy.add(in);
//
//                futures.add(ex.submit(new TreePredictionThread(i, dimensions, trees.get(i), testCopy)));
//            }
//
//            for (Future<MultiThreadPredictionHolder> f : futures) {
//                MultiThreadPredictionHolder h = f.get();
//                d[h.c]++;
//
//                if (interpSavePath != null && base instanceof TimeSeriesTree){
//                    interpData.add(h.al);
//                    interpTreePreds.add(h.c);
//                }
//            }
        }
        else {
            //Build transformed instance
            for (int i = 0; i < trees.size(); i++) {
                Catch22 c22 = new Catch22();
                c22.setOutlierNormalise(outlierNorm);
                boolean[] usedAtts = attUsage.get(i);
                Instances testHolder = testHolders.get(i);

                int p = 0;
                for (int r = 0; r < representations.length; r++) {
                    double[] series = representations[r]; //todo mv

                    for (int a = 0; a < numAttributes; a++) {
                        for (int j = 0; j < intervals.get(i)[r][a].size(); j++) {
                            if (!usedAtts[p]) {
                                testHolder.instance(0).setValue(p, 0);
                                p++;
                                continue;
                            }

                            int[] interval = intervals.get(i)[r][a].get(j);
                            if (subsampleAtts.get(i).get(a) < 22){
                                double[] intervalArray = Arrays.copyOfRange(series, interval[0], interval[1] + 1);
                                testHolder.instance(0).setValue(p,
                                        c22.getSummaryStatByIndex(subsampleAtts.get(i).get(a), j, intervalArray));
                            }
                            else {
                                testHolder.instance(0).setValue(p,
                                        FeatureSet.calcFeatureByIndex(subsampleAtts.get(i).get(a), interval[0],
                                                interval[1], series));
                            }

                            p++;
                        }
                    }
                }

                int c;
                if (interpSavePath != null && base instanceof TimeSeriesTree) {
                    ArrayList<double[]> al = new ArrayList<>();
                    c = (int) ((TimeSeriesTree) trees.get(i)).classifyInstance(testHolder.instance(0), al);
                    interpData.add(al);
                    interpTreePreds.add(c);
                } else {
                    c = (int) trees.get(i).classifyInstance(testHolder.instance(0));
                }
                d[c]++;
            }
        }

        double sum = 0;
        for(double x: d)
            sum += x ;
        for(int i = 0; i < d.length; i++)
            d[i] = d[i]/sum;

        if (interpSavePath != null) {
//            interpSeries = dimensions[0];
//            interpPred = argMax(d,rand);
        }

        return d;
    }

    /**
     * Find class probabilities of an instance using the trained model.
     *
     * @param ins weka Instance object
     * @return array of doubles: probability of each class
     * @throws Exception failure to classify
     */
    @Override //AbstractClassifier
    public double[] distributionForInstance(Instance ins) throws Exception {
        return distributionForInstance(Converter.fromArff(ins));
    }

    /**
     * Classify an instance using the trained model.
     *
     * @param ins TimeSeriesInstance object
     * @return predicted class value
     * @throws Exception failure to classify
     */
    @Override //TSClassifier
    public double classifyInstance(TimeSeriesInstance ins) throws Exception {
        double[] probs = distributionForInstance(ins);
        return findIndexOfMax(probs, rand);
    }

    /**
     * Classify an instance using the trained model.
     *
     * @param ins weka Instance object
     * @return predicted class value
     * @throws Exception failure to classify
     */
    @Override //AbstractClassifier
    public double classifyInstance(Instance ins) throws Exception {
        return classifyInstance(Converter.fromArff(ins));
    }

    /**
     * Set the train time limit for a contracted classifier.
     *
     * @param amount contract time in nanoseconds
     */
    @Override //TrainTimeContractable
    public void setTrainTimeLimit(long amount) {
        contractTime = amount;
        trainTimeContract = true;
    }

    /**
     * Check if a contracted classifier is within its train time limit.
     *
     * @param start classifier build start time
     * @return true if within the contract or not contracted, false otherwise.
     */
    @Override //TrainTimeContractable
    public boolean withinTrainContract(long start){
        if(contractTime<=0) return true; //Not contracted
        return System.nanoTime()-start-checkpointTimeDiff < contractTime;
    }

    /**
     * Set the path to save checkpoint files to.
     *
     * @param path string for full path for the directory to store checkpointed files
     * @return true if valid path, false otherwise
     */
    @Override //Checkpointable
    public boolean setCheckpointPath(String path) {
        boolean validPath = Checkpointable.super.createDirectories(path);
        if(validPath){
            checkpointPath = path;
            checkpoint = true;
        }
        return validPath;
    }

    /**
     * Set the time between checkpoints in hours.
     *
     * @param t number of hours between checkpoints
     * @return true
     */
    @Override //Checkpointable
    public boolean setCheckpointTimeHours(int t){
        checkpointTime=TimeUnit.NANOSECONDS.convert(t,TimeUnit.HOURS);
        return true;
    }

    /**
     * Serialises this CIF object to the specified path.
     *
     * @param path save path for object
     * @throws Exception object fails to save
     */
    @Override //Checkpointable
    public void saveToFile(String path) throws Exception{
        lastCheckpointTime = System.nanoTime();
        Checkpointable.super.saveToFile(path + "CIF" + seed + "temp.ser");
        File file = new File(path + "CIF" + seed + "temp.ser");
        File file2 = new File(path + "CIF" + seed + ".ser");
        file2.delete();
        file.renameTo(file2);
        if (internalContractCheckpointHandling) checkpointTimeDiff += System.nanoTime()-lastCheckpointTime;
    }

    /**
     * Copies values from a loaded CIF object into this object.
     *
     * @param obj a CIF object
     * @throws Exception if obj is not an instance of CIF
     */
    @Override //Checkpointable
    public void copyFromSerObject(Object obj) throws Exception {
//        if (!(obj instanceof SCIF))
//            throw new Exception("The SER file is not an instance of TSF");
//        SCIF saved = ((SCIF)obj);
//        System.out.println("Loading CIF" + seed + ".ser");
//
//        try {
//            numClassifiers = saved.numClassifiers;
//            attSubsampleSize = saved.attSubsampleSize;
//            numAttributes = saved.numAttributes;
//            startNumAttributes = saved.startNumAttributes;
//            subsampleAtts = saved.subsampleAtts;
//            outlierNorm = saved.outlierNorm;
//            useSummaryStats = saved.useSummaryStats;
//            numIntervals = saved.numIntervals;
//            //numIntervalsFinder = saved.numIntervalsFinder;
//            minIntervalLength = saved.minIntervalLength;
//            //minIntervalLengthFinder = saved.minIntervalLengthFinder;
//            maxIntervalLength = saved.maxIntervalLength;
//            //maxIntervalLengthFinder = saved.maxIntervalLengthFinder;
//            trees = saved.trees;
//            base = saved.base;
//            attUsage = saved.attUsage;
//            intervals = saved.intervals;
//            //testHolder = saved.testHolder;
//            bagging = saved.bagging;
//            oobCounts = saved.oobCounts;
//            trainDistributions = saved.trainDistributions;
//            //checkpoint = saved.checkpoint;
//            //checkpointPath = saved.checkpointPath
//            //checkpointTime = saved.checkpointTime;
//            //lastCheckpointTime = saved.lastCheckpointTime;
//            //checkpointTimeDiff = saved.checkpointTimeDiff;
//            //internalContractCheckpointHandling = saved.internalContractCheckpointHandling;
//            trainTimeContract = saved.trainTimeContract;
//            if (internalContractCheckpointHandling) contractTime = saved.contractTime;
//            maxClassifiers = saved.maxClassifiers;
//            //numThreads = saved.numThreads;
//            //multiThread = saved.multiThread;
//            //ex = saved.ex;
//            visSavePath = saved.visSavePath;
//            visNumTopAtts = saved.visNumTopAtts;
//            interpSavePath = saved.interpSavePath;
//            //interpData = saved.interpData;
//            //interpTreePreds = saved.interpTreePreds;
//            //interpCount = saved.interpCount;
//            //interpSeries = saved.interpSeries;
//            //interpPred = saved.interpPred;
//            seriesLength = saved.seriesLength;
//            numInstances = saved.numInstances;
//            numDimensions = saved.numDimensions;
//            intervalDimensions = saved.intervalDimensions;
//            //c22 = saved.c22;
//
//            trainResults = saved.trainResults;
//            if (!internalContractCheckpointHandling) trainResults.setBuildTime(System.nanoTime());
//            seedClassifier = saved.seedClassifier;
//            seed = saved.seed;
//            rand = saved.rand;
//            estimateOwnPerformance = saved.estimateOwnPerformance;
//            estimator = saved.estimator;
//            numClasses = saved.numClasses;
//
//            if (internalContractCheckpointHandling) checkpointTimeDiff = saved.checkpointTimeDiff
//                    + (System.nanoTime() - saved.lastCheckpointTime);
//            lastCheckpointTime = System.nanoTime();
//        }catch(Exception ex){
//            System.out.println("Unable to assign variables when loading serialised file");
//        }
    }

    /**
     * Returns the default set of possible parameter values for use in setOptions when tuning.
     *
     * @return default parameter space for tuning
     */
    @Override //Tunable
    public ParameterSpace getDefaultParameterSearchSpace(){
        ParameterSpace ps=new ParameterSpace();
        String[] numAtts={"8","16","25"};
        ps.addParameter("-A", numAtts);
        String[] maxIntervalLengths={"0.5","0.75","1"};
        ps.addParameter("-L", maxIntervalLengths);
        return ps;
    }

    /**
     * Parses a given list of options. Valid options are:
     *
     * -A  The number of attributes to subsample as an integer from 1-25.
     * -L  Max interval length as a proportion of series length as a double from 0-1.
     *
     * @param options the list of options as an array of strings
     * @throws Exception if an option value is invalid
     */
    @Override //AbstractClassifier
    public void setOptions(String[] options) throws Exception{
        System.out.println(Arrays.toString(options));

        String numAttsString = Utils.getOption("-A", options);
        System.out.println(numAttsString);
        if (numAttsString.length() != 0)
            attSubsampleSize = Integer.parseInt(numAttsString);
    }

    /**
     * Enables multi threading with a set number of threads to use.
     *
     * @param numThreads number of threads available for multi threading
     */
    @Override //MultiThreadable
    public void enableMultiThreading(int numThreads) {
        if (numThreads > 1) {
            this.numThreads = numThreads;
            multiThread = true;
        } else {
            this.numThreads = 1;
            multiThread = false;
        }
    }

    /**
     * Creates and stores a path to save visualisation files to.
     *
     * @param path String directory path
     * @return true if path is valid, false otherwise.
     */
    @Override //Visualisable
    public boolean setVisualisationSavePath(String path) {
        boolean validPath = Visualisable.super.createVisualisationDirectories(path);
        if(validPath){
            visSavePath = path;
        }
        return validPath;
    }

    /**
     * Finds the temporal importance curves for model. Outputs a matplotlib figure using the visCIF.py file using the
     * generated curves.
     *
     * @return true if python file to create visualisation ran, false if no path set or invalid classifier
     * @throws Exception if failure to set path or create visualisation
     */
    @Override //Visualisable
    public boolean createVisualisation() throws Exception {
//        if (!(base instanceof TimeSeriesTree)) {
//            System.err.println("CIF temporal importance curve only available for time series tree.");
//            return false;
//        }
//
//        if (visSavePath == null){
//            System.err.println("CIF visualisation save path not set.");
//            return false;
//        }
//
//        boolean isMultivariate = numDimensions > 1;
//        int[] dimCount = null;
//        if (isMultivariate) dimCount = new int[numDimensions];
//
//        //get information gain from all tree node splits for each attribute/time point
//        double[][][] curves = new double[startNumAttributes][numDimensions][seriesLength];
//        for (int i = 0; i < trees.size(); i++){
//            TimeSeriesTree tree = (TimeSeriesTree)trees.get(i);
//            ArrayList<Double>[] sg = tree.getTreeSplitsGain();
//
//            for (int n = 0; n < sg[0].size(); n++){
//                double split = sg[0].get(n);
//                double gain = sg[1].get(n);
//                int interval = (int)(split/numAttributes);
//                int att = subsampleAtts.get(i).get((int)(split%numAttributes));
//                int dim = intervalDimensions.get(i).get(interval);
//
//                if (isMultivariate) dimCount[dim]++;
//
//                for (int j = intervals.get(i)[interval][0]; j <= intervals.get(i)[interval][1]; j++){
//                    curves[att][dim][j] += gain;
//                }
//            }
//        }
//
//        if (isMultivariate){
//            OutFile of = new OutFile(visSavePath + "/dims" + seed + ".txt");
//            of.writeLine(Arrays.toString(dimCount));
//            of.closeFile();
//        }
//
//        OutFile of = new OutFile(visSavePath + "/vis" + seed + ".txt");
//        for (int i = 0; i < numDimensions; i++) {
//            for (int n = 0; n < startNumAttributes; n++) {
//                switch (n) {
//                    case 22:
//                        of.writeLine("Mean");
//                        break;
//                    case 23:
//                        of.writeLine("Standard Deviation");
//                        break;
//                    case 24:
//                        of.writeLine("Slope");
//                        break;
//                    default:
//                        of.writeLine(Catch22.getSummaryStatNameByIndex(n));
//                }
//                of.writeLine(Integer.toString(i));
//                of.writeLine(Arrays.toString(curves[n][i]));
//            }
//        }
//        of.closeFile();
//
//        //run python file to output temporal importance curves graph
//        Process p = Runtime.getRuntime().exec("py src/main/python/visCIF.py \"" +
//                visSavePath.replace("\\", "/")+ "\" " + seed + " " + startNumAttributes
//                + " " + numDimensions + " " + visNumTopAtts);
//
//        if (debug) {
//            System.out.println("CIF vis python output:");
//            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
//            BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
//            System.out.println("output : ");
//            String outLine = out.readLine();
//            while (outLine != null) {
//                System.out.println(outLine);
//                outLine = out.readLine();
//            }
//            System.out.println("error : ");
//            String errLine = err.readLine();
//            while (errLine != null) {
//                System.out.println(errLine);
//                errLine = err.readLine();
//            }
//        }

        return true;
    }

    /**
     * Stores a path to save interpretability files to.
     *
     * @param path String directory path
     * @return true if path is valid, false otherwise.
     */
    @Override //Interpretable
    public boolean setInterpretabilitySavePath(String path) {
        boolean validPath = Interpretable.super.createInterpretabilityDirectories(path);
        if(validPath){
            interpSavePath = path;
        }
        return validPath;
    }

    /**
     * Outputs a summary/visualisation of how the last classifier prediction was made to a set path. Runs
     * interpretabilityCIF.py for visualisations.
     *
     * @return true if python file to create visualisation ran, false if no path set or invalid classifier
     * @throws Exception if failure to set path or output files
     */
    @Override //Interpretable
    public boolean lastClassifiedInterpretability() throws Exception {
//        if (!(base instanceof TimeSeriesTree)) {
//            System.err.println("CIF interpretability output only available for time series tree.");
//            return false;
//        }
//
//        if (interpSavePath == null){
//            System.err.println("CIF interpretability output save path not set.");
//            return false;
//        }
//
//        OutFile of = new OutFile(interpSavePath + "pred" + seed + "-" + interpCount
//                + ".txt");
//        //output test series
//        of.writeLine("Series");
//        of.writeLine(Arrays.toString(interpSeries));
//        //output the nodes visited for each tree
//        for (int i = 0; i < interpData.size(); i++){
//            of.writeLine("Tree " + i + " - " + interpData.get(i).size() + " nodes - pred " + interpTreePreds.get(i));
//            for (int n = 0; n < interpData.get(i).size(); n++){
//                if (n == interpData.get(i).size()-1){
//                    of.writeLine(Arrays.toString(interpData.get(i).get(n)));
//                }
//                else {
//                    TimeSeriesTree tree = (TimeSeriesTree)trees.get(i);
//                    double[] arr = new double[5];
//                    double[] nodeData = interpData.get(i).get(n);
//
//                    int interval = (int) (nodeData[0] / numAttributes);
//                    int att = (int) (nodeData[0] % numAttributes);
//                    att = subsampleAtts.get(i).get(att);
//
//                    arr[0] = att;
//                    arr[1] = intervals.get(i)[interval][0];
//                    arr[2] = intervals.get(i)[interval][1];
//                    if (tree.getNormalise()){
//                        arr[3] = nodeData[1] * tree.getNormStdev((int) nodeData[0])
//                                + tree.getNormMean((int) nodeData[0]);
//                    }
//                    else {
//                        arr[3] = nodeData[1];
//                    }
//                    arr[4] = nodeData[2];
//
//                    of.writeLine(Arrays.toString(arr));
//                }
//            }
//        }
//        of.closeFile();
//
//        //run python file to output graph displaying important attributes and intervals for test series
//        Process p = Runtime.getRuntime().exec("py src/main/python/interpretabilityCIF.py \"" +
//                interpSavePath.replace("\\", "/")+ "\" " + seed + " " + interpCount
//                + " " + trees.size() + " " + seriesLength + " " + startNumAttributes + " " + interpPred);
//
//        interpCount++;
//
//        if (debug) {
//            System.out.println("CIF interp python output:");
//            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
//            BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
//            System.out.println("output : ");
//            String outLine = out.readLine();
//            while (outLine != null) {
//                System.out.println(outLine);
//                outLine = out.readLine();
//            }
//            System.out.println("error : ");
//            String errLine = err.readLine();
//            while (errLine != null) {
//                System.out.println(errLine);
//                errLine = err.readLine();
//            }
//        }

        return true;
    }

    /**
     * Get a unique indentifier for the last prediction made, used for filenames etc.
     *
     * @return int ID for the last prediction
     */
    @Override //Interpretable
    public int getPredID(){
        return interpCount;
    }

    /**
     * Nested class to find and store three simple summary features for an interval
     */
    public static class FeatureSet{
        public static double calcFeatureByIndex(int idx, int start, int end, double[] data) {
            switch (idx){
                case 22: return calcMean(start, end, data);
                case 23: return calcMedian(start, end, data);
                case 24: return calcStandardDeviation(start, end, data);
                case 25: return calcSlope(start, end, data);
                case 26: return calcInterquartileRange(start, end, data);
                case 27: return calcMin(start, end, data);
                case 28: return calcMax(start, end, data);
                default: return Double.NaN;
            }
        }

        public static double calcMean(int start, int end, double[] data){
            double sumY = 0;
            for(int i=start;i<=end;i++) {
                sumY += data[i];
            }

            int length = end-start+1;
            return sumY/length;
        }

        public static double calcMedian(int start, int end, double[] data){
            ArrayList<Double> sortedData = new ArrayList<>(end-start+1);
            for(int i=start;i<=end;i++){
                sortedData.add(data[i]);
            }

            return median(sortedData, false); //sorted in function
        }

        public static double calcStandardDeviation(int start, int end, double[] data){
            double sumY = 0;
            double sumYY = 0;
            for(int i=start;i<=end;i++) {
                sumY += data[i];
                sumYY += data[i] * data[i];
            }

            int length = (end-start)+1;
            return (sumYY-(sumY*sumY)/length)/(length-1);
        }

        public static double calcSlope(int start, int end, double[] data){
            double sumY = 0;
            double sumX = 0, sumXX = 0, sumXY = 0;
            for(int i=start;i<=end;i++) {
                sumY += data[i];
                sumX+=(i-start);
                sumXX+=(i-start)*(i-start);
                sumXY+=data[i]*(i-start);
            }

            int length = end-start+1;
            double slope=(sumXY-(sumX*sumY)/length);
            double denom=sumXX-(sumX*sumX)/length;
            slope = denom == 0 ? 0 : slope/denom;
            return slope;
        }

        public static double calcInterquartileRange(int start, int end, double[] data){
            ArrayList<Double> sortedData = new ArrayList<>(end-start+1);
            for(int i=start;i<=end;i++){
                sortedData.add(data[i]);
            }
            Collections.sort(sortedData);

            int length = end-start+1;
            ArrayList<Double> left = new ArrayList<>(length / 2 + 1);
            ArrayList<Double> right = new ArrayList<>(length / 2 + 1);
            if (length % 2 == 1) {
                for (int i = 0; i <= length / 2; i++){
                    left.add(sortedData.get(i));
                }
            }
            else {
                for (int i = 0; i < length / 2; i++){
                    left.add(sortedData.get(i));
                }

            }
            for (int i = length / 2; i < sortedData.size(); i++){
                right.add(sortedData.get(i));
            }

            return median(right, false) - median(left, false);
        }

        public static double calcMin(int start, int end, double[] data){
            double min = Double.MAX_VALUE;
            for(int i=start;i<=end;i++){
                if (data[i] < min) min = data[i];
            }
            return min;
        }

        public static double calcMax(int start, int end, double[] data){
            double max = -999999999;
            for(int i=start;i<=end;i++){
                if (data[i] > max) max = data[i];
            }
            return max;
        }
    }

    /**
     * Class to hold data about a CIF tree when multi threading.
     */
    private static class MultiThreadBuildHolder {
        ArrayList<Integer> subsampleAtts;
        ArrayList<Integer> intervalDimensions;
        Classifier tree;
        int[][] interval;
        boolean[] attUsage;

        double[][] trainDistribution;
        int[] oobCounts;
        long errorTime;

        public MultiThreadBuildHolder() { }
    }

    /**
     * Class to build a CIF tree when multi threading.
     */
    private class TreeBuildThread implements Callable<MultiThreadBuildHolder> {
        int i;
        double[][][] dimensions;
        int[] classVals;
        Instances result;

        public TreeBuildThread(int i, double[][][] dimensions, int[] classVals, Instances result){
            this.i = i;
            this.dimensions = dimensions;
            this.classVals = classVals;
            this.result = result;
        }

        /**
         *   generate random intervals
         *   do the transfrorms
         *   build the classifier
         **/
        @Override
        public MultiThreadBuildHolder call() throws Exception{
//            MultiThreadBuildHolder h = new MultiThreadBuildHolder();
//            Random rand = new Random(seed + i * numClassifiers);
//
//            //1. Select random intervals for tree i
//
//            int[][] interval = new int[numIntervals][2];  //Start and end
//
//            for (int j = 0; j < numIntervals; j++) {
//                if (rand.nextBoolean()) {
//                    interval[j][0] = rand.nextInt(seriesLength - minIntervalLength); //Start point
//
//                    int range = Math.min(seriesLength - interval[j][0], maxIntervalLength);
//                    int length = rand.nextInt(range - minIntervalLength) + minIntervalLength;
//                    interval[j][1] = interval[j][0] + length;
//                } else {
//                    interval[j][1] = rand.nextInt(seriesLength - minIntervalLength)
//                            + minIntervalLength; //Start point
//
//                    int range = Math.min(interval[j][1], maxIntervalLength);
//                    int length;
//                    if (range - minIntervalLength == 0) length = 3;
//                    else length = rand.nextInt(range - minIntervalLength) + minIntervalLength;
//                    interval[j][0] = interval[j][1] - length;
//                }
//            }
//
//            //If bagging find instances with replacement
//
//            int[] instInclusions = null;
//            boolean[] inBag = null;
//            if (bagging) {
//                inBag = new boolean[numInstances];
//                instInclusions = new int[numInstances];
//
//                for (int n = 0; n < numInstances; n++) {
//                    instInclusions[rand.nextInt(numInstances)]++;
//                }
//
//                for (int n = 0; n < numInstances; n++) {
//                    if (instInclusions[n] > 0) {
//                        inBag[n] = true;
//                    }
//                }
//            }
//
//            //find attributes to subsample
//            ArrayList<Integer> subsampleAtts = new ArrayList<>();
//            for (int n = 0; n < startNumAttributes; n++){
//                subsampleAtts.add(n);
//            }
//
//            while (subsampleAtts.size() > numAttributes){
//                subsampleAtts.remove(rand.nextInt(subsampleAtts.size()));
//            }
//
//            //find dimensions for each interval
//            ArrayList<Integer> intervalDimensions = new ArrayList<>();
//            for (int n = 0; n < numIntervals; n++) {
//                intervalDimensions.add(rand.nextInt(numDimensions));
//            }
//            Collections.sort(intervalDimensions);
//
//            h.subsampleAtts = subsampleAtts;
//            h.intervalDimensions = intervalDimensions;
//
//            //For bagging
//            int instIdx = 0;
//            int lastIdx = -1;
//
//            //2. Generate and store attributes
//            for (int k = 0; k < numInstances; k++) {
//                //For each instance
//
//                if (bagging) {
//                    boolean sameInst = false;
//
//                    while (true) {
//                        if (instInclusions[instIdx] == 0) {
//                            instIdx++;
//                        } else {
//                            instInclusions[instIdx]--;
//
//                            if (instIdx == lastIdx) {
//                                result.set(k, new DenseInstance(result.instance(k - 1)));
//                                sameInst = true;
//                            } else {
//                                lastIdx = instIdx;
//                            }
//
//                            break;
//                        }
//                    }
//
//                    if (sameInst) continue;
//
//                    result.instance(k).setValue(result.classIndex(), classVals[instIdx]);
//                } else {
//                    instIdx = k;
//                }
//
//                for (int j = 0; j < numIntervals; j++) {
//                    //extract the interval
//                    double[] series = dimensions[instIdx][intervalDimensions.get(j)];
//
//                    FeatureSet f = new FeatureSet();
//                    double[] intervalArray = Arrays.copyOfRange(series, interval[j][0], interval[j][1] + 1);
//
//                    //process features
//                    Catch22 c22 = new Catch22();
//
//                    for (int g = 0; g < numAttributes; g++) {
//                        if (subsampleAtts.get(g) < 22) {
//                            result.instance(k).setValue(j * numAttributes + g,
//                                    c22.getSummaryStatByIndex(subsampleAtts.get(g), j, intervalArray));
//                        } else {
//                            if (!f.calculatedFeatures) {
//                                f.setFeatures(series, interval[j][0], interval[j][1]);
//                            }
//
//                            switch (subsampleAtts.get(g)) {
//                                case 22:
//                                    result.instance(k).setValue(j * numAttributes + g, f.mean);
//                                    break;
//                                case 23:
//                                    result.instance(k).setValue(j * numAttributes + g, f.stDev);
//                                    break;
//                                case 24:
//                                    result.instance(k).setValue(j * numAttributes + g, f.slope);
//                                    break;
//                                default:
//                                    throw new Exception("att subsample basic features broke");
//                            }
//                        }
//                    }
//                }
//            }
//
//            if (bagging) {
//                result.randomize(rand);
//            }
//
//            //3. Create and build tree using all the features. Feature selection
//            Classifier tree = AbstractClassifier.makeCopy(base);
//            if (seedClassifier && tree instanceof Randomizable)
//                ((Randomizable) tree).setSeed(seed * (i + 1));
//
//            tree.buildClassifier(result);
//
//            boolean[] attUsage;
//            if (base instanceof TimeSeriesTree) {
//                attUsage = ((TimeSeriesTree)tree).getAttributesUsed();
//            }
//            else{
//                attUsage = new boolean[numAttributes*numIntervals];
//                Arrays.fill(attUsage,true);
//            }
//
//            h.attUsage = attUsage;
//
//            if (bagging && getEstimateOwnPerformance()) {
//                long t1 = System.nanoTime();
//                int[] oobCounts = new int[numInstances];
//                double[][] trainDistributions = new double[numInstances][numClasses];
//
//                for (int n = 0; n < numInstances; n++) {
//                    if (inBag[n])
//                        continue;
//
//                    for (int j = 0; j < numIntervals; j++) {
//                        double[] series = dimensions[n][intervalDimensions.get(j)];
//
//                        FeatureSet f = new FeatureSet();
//                        double[] intervalArray = Arrays.copyOfRange(series, interval[j][0], interval[j][1] + 1);
//
//                        for (int g = 0; g < numAttributes; g++) {
//                            if (!attUsage[j * numAttributes + g]) {
//                                testHolder.instance(0).setValue(j * numAttributes + g, 0);
//                                continue;
//                            }
//
//                            if (subsampleAtts.get(g) < 22) {
//                                testHolder.instance(0).setValue(j * numAttributes + g,
//                                        c22.getSummaryStatByIndex(subsampleAtts.get(g), j, intervalArray));
//                            } else {
//                                if (!f.calculatedFeatures) {
//                                    f.setFeatures(series, interval[j][0], interval[j][1]);
//                                }
//                                switch (subsampleAtts.get(g)) {
//                                    case 22:
//                                        testHolder.instance(0).setValue(j * numAttributes + g, f.mean);
//                                        break;
//                                    case 23:
//                                        testHolder.instance(0).setValue(j * numAttributes + g, f.stDev);
//                                        break;
//                                    case 24:
//                                        testHolder.instance(0).setValue(j * numAttributes + g, f.slope);
//                                        break;
//                                    default:
//                                        throw new Exception("att subsample basic features broke");
//                                }
//                            }
//                        }
//                    }
//
//                    double[] newProbs = tree.distributionForInstance(testHolder.instance(0));
//                    oobCounts[n]++;
//                    for (int k = 0; k < newProbs.length; k++)
//                        trainDistributions[n][k] += newProbs[k];
//                }
//
//                h.oobCounts = oobCounts;
//                h.trainDistribution = trainDistributions;
//                h.errorTime = System.nanoTime() - t1;
//            }
//
//            h.tree = tree;
//            h.interval = interval;
//
//            return h;
            return null;
        }
    }

    /**
     * Class to hold data about a CIF tree when multi threading.
     */
    private static class MultiThreadPredictionHolder {
        int c;

        ArrayList<double[]> al;

        public MultiThreadPredictionHolder() { }
    }

    /**
     * Class to make a class prediction using a CIF tree when multi threading.
     */
    private class TreePredictionThread implements Callable<MultiThreadPredictionHolder> {
        int i;
        double[][] dimensions;
        Classifier tree;
        Instances testHolder;

        public TreePredictionThread(int i, double[][] dimensions, Classifier tree, Instances testHolder){
            this.i = i;
            this.dimensions = dimensions;
            this.tree = tree;
            this.testHolder = testHolder;
        }

        @Override
        public MultiThreadPredictionHolder call() throws Exception{
//            MultiThreadPredictionHolder h = new MultiThreadPredictionHolder();
//
//            //Build transformed instance
//            Catch22 c22 = new Catch22();
//            c22.setOutlierNormalise(outlierNorm);
//            boolean[] usedAtts = attUsage.get(i);
//
//            for (int j = 0; j < numIntervals; j++) {
//                double[] series = dimensions[intervalDimensions.get(i).get(j)];
//
//                FeatureSet f = new FeatureSet();
//                double[] intervalArray = Arrays.copyOfRange(series, intervals.get(i)[j][0],
//                        intervals.get(i)[j][1] + 1);
//
//                for (int g = 0; g < numAttributes; g++) {
//                    if (!usedAtts[j * numAttributes + g]) {
//                        testHolder.instance(0).setValue(j * numAttributes + g, 0);
//                        continue;
//                    }
//
//                    if (subsampleAtts.get(i).get(g) < 22) {
//                        testHolder.instance(0).setValue(j * numAttributes + g,
//                                c22.getSummaryStatByIndex(subsampleAtts.get(i).get(g), j, intervalArray));
//                    } else {
//                        if (!f.calculatedFeatures) {
//                            f.setFeatures(series, intervals.get(i)[j][0], intervals.get(i)[j][1]);
//                        }
//                        switch (subsampleAtts.get(i).get(g)) {
//                            case 22:
//                                testHolder.instance(0).setValue(j * numAttributes + g, f.mean);
//                                break;
//                            case 23:
//                                testHolder.instance(0).setValue(j * numAttributes + g, f.stDev);
//                                break;
//                            case 24:
//                                testHolder.instance(0).setValue(j * numAttributes + g, f.slope);
//                                break;
//                            default:
//                                throw new Exception("att subsample basic features broke");
//                        }
//                    }
//                }
//            }
//
//            if (interpSavePath != null && base instanceof TimeSeriesTree) {
//                ArrayList<double[]> al = new ArrayList<>();
//                h.c = (int) ((TimeSeriesTree) tree).classifyInstance(testHolder.instance(0), al);
//                h.al = al;
//            } else {
//                h.c = (int) tree.classifyInstance(testHolder.instance(0));
//            }
//
//            return h;
            return null;
        }
    }

    /**
     * Development tests for the CIF classifier.
     *
     * @param arg arguments, unused
     * @throws Exception if tests fail
     */
    public static void main(String[] arg) throws Exception{
        String dataLocation="D:\\CMP Machine Learning\\Datasets\\UnivariateARFF\\";
        String problem="ItalyPowerDemand";
        Instances train= DatasetLoading.loadDataNullable(dataLocation+problem+"\\"+problem+"_TRAIN");
        Instances test= DatasetLoading.loadDataNullable(dataLocation+problem+"\\"+problem+"_TEST");
        SCIF c = new SCIF();
        c.setSeed(0);
        c.bagging = true;
        c.classBalancing = false;
        c.extraDims = false;
        c.supervisedIntervals = false;
        //c.estimateOwnPerformance = true;
        //c.estimator = EstimatorMethod.OOB;
        double a;
        long t1 = System.nanoTime();
        c.buildClassifier(train);
        System.out.println("Train time="+(System.nanoTime()-t1)*1e-9);
//        System.out.println("build ok: original atts = "+(train.numAttributes()-1)+" new atts = "
//                +(c.testHolder.numAttributes()-1)+" num trees = "+c.trees.size()+" num intervals = "+c.numIntervals);
        System.out.println("recorded times: train time = "+(c.trainResults.getBuildTime()*1e-9)+" estimate time = "
                +(c.trainResults.getErrorEstimateTime()*1e-9)
                +" both = "+(c.trainResults.getBuildPlusEstimateTime()*1e-9));
        a= ClassifierTools.accuracy(test, c);
        System.out.println("Test Accuracy = "+a);
        System.out.println("Train Accuracy = "+c.trainResults.getAcc());
    }
}