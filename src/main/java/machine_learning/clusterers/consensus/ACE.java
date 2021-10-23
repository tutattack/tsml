package machine_learning.clusterers.consensus;

import evaluation.storage.ClustererResults;
import experiments.data.DatasetLoading;
import machine_learning.clusterers.KMeans;
import tsml.clusterers.EnhancedAbstractClusterer;
import utilities.GenericTools;
import weka.clusterers.NumberOfClustersRequestable;
import weka.core.Instance;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.Arrays;

import static utilities.ArrayUtilities.sum;
import static utilities.ArrayUtilities.unique;

public class ACE extends ConsensusClusterer implements LoadableConsensusClusterer, NumberOfClustersRequestable {

    private double alpha = 0.8;
    private double alphaIncrement = 0.1;
    private double alphaMin = 0.6;
    private double alpha2 = 0.7;
    private int k = 2;

    private int[] newLabels;

    public ACE(EnhancedAbstractClusterer[] clusterers) {
        super(clusterers);
    }

    public ACE(ArrayList<EnhancedAbstractClusterer> clusterers) {
        super(clusterers);
    }

    @Override
    public int numberOfClusters() throws Exception {
        return k;
    }

    @Override
    public void setNumClusters(int numClusters) throws Exception {
        k = numClusters;
    }

    @Override
    public void buildClusterer(Instances data) throws Exception {
        super.buildClusterer(data);

//        if (buildClusterers){
//            for (EnhancedAbstractClusterer clusterer: clusterers){
//                clusterer.buildClusterer(data);
//            }
//        }

        ArrayList<Integer>[][] ensembleClusters = new ArrayList[clusterers.length][];
//        for (int i = 0; i < ensembleClusters.length; i++) {
//            ensembleClusters[i] = clusterers[i].getClusters();
//        }

        buildEnsemble(ensembleClusters, data.numInstances());
    }

    @Override
    public void buildFromFile(String[] directoryPaths) throws Exception {
        ArrayList<Integer>[][] ensembleClusters = new ArrayList[directoryPaths.length][];
        int numInstances = -1;

        for (int i = 0; i < directoryPaths.length; i++) {
            ClustererResults r = new ClustererResults(directoryPaths[i] + "trainFold" + seed + ".csv");
            if (i == 0)
                numInstances = r.numInstances();

            ensembleClusters[i] = new ArrayList[r.getNumClusters()];

            for (int n = 0; n < ensembleClusters[i].length; n++) {
                ensembleClusters[i][n] = new ArrayList();
            }

            int[] fileAssignments = r.getClusterValuesAsIntArray();
            for (int n = 0; n < fileAssignments.length; n++) {
                ensembleClusters[i][fileAssignments[n]].add(n);
            }
        }

        buildEnsemble(ensembleClusters, numInstances);
    }

    @Override
    public int clusterInstance(Instance inst) throws Exception {
        return 0;
    }

    @Override
    public int[] clusterFromFile(String[] directoryPaths) throws Exception {
        return new int[0];
    }

    private void buildEnsemble(ArrayList<Integer>[][] ensembleClusters, int numInstances) throws Exception {
        numInstances = 10;
        ensembleClusters = new ArrayList[3][3];
        ensembleClusters[0][0] = new ArrayList(Arrays.asList(5, 6, 7, 8));
        ensembleClusters[0][1] = new ArrayList(Arrays.asList(0, 1, 9));
        ensembleClusters[0][2] = new ArrayList(Arrays.asList(2, 3, 4));
        ensembleClusters[1][0] = new ArrayList(Arrays.asList(2, 3, 4));
        ensembleClusters[1][1] = new ArrayList(Arrays.asList(6, 7, 8));
        ensembleClusters[1][2] = new ArrayList(Arrays.asList(0, 1, 5, 9));
        ensembleClusters[2][0] = new ArrayList(Arrays.asList(0, 1, 2, 9));
        ensembleClusters[2][1] = new ArrayList(Arrays.asList(5, 6, 7, 8));
        ensembleClusters[2][2] = new ArrayList(Arrays.asList(3, 4));

        int clusterCount = 0;
        ArrayList<ArrayList<double[]>> binaryClusterMembership = new ArrayList<>(ensembleClusters.length);
        for (ArrayList<Integer>[] memberClusters: ensembleClusters){
            clusterCount += memberClusters.length;
            ArrayList<double[]> binaryClusters = new ArrayList<>(memberClusters.length);
            binaryClusterMembership.add(binaryClusters);

            for (ArrayList<Integer> memberCluster : memberClusters) {
                double[] binaryCluster = new double[numInstances];
                binaryClusters.add(binaryCluster);
                for (int n : memberCluster) {
                    binaryCluster[n] = 1;
                }
            }
        }

        if (k > clusterCount){
            throw new Exception("K is greater than the total number of clusters in the ensemble.");
        }

        newLabels = new int[clusterCount];
        for (int i = 1; i < clusterCount; i++){
            newLabels[i] = i;
        }

        boolean stage1 = true;
        while (true) {
            // find the similarity of each cluster from different ensemble members
            double[][] clusterSimilarities = new double[clusterCount][];
            int countI = 0;
            for (int i = 0; i < binaryClusterMembership.size(); i++) {
                for (int n = 0; n < binaryClusterMembership.get(i).size(); n++) {
                    clusterSimilarities[countI + n] = new double[countI];
                }

                int countN = 0;
                for (int n = 0; n < i; n++) {
                    for (int j = 0; j < binaryClusterMembership.get(i).size(); j++) {
                        for (int k = 0; k < binaryClusterMembership.get(n).size(); k++) {
                            clusterSimilarities[countI + j][countN + k] = setCorrelation(
                                    binaryClusterMembership.get(i).get(j), binaryClusterMembership.get(n).get(k),
                                    numInstances);
                        }
                    }
                    countN += binaryClusterMembership.get(n).size();
                }
                countI += binaryClusterMembership.get(i).size();
            }

            // update alpha to the max similarity value if not using initial clusters
            if (!stage1){
                alpha = maxSimilarity(clusterSimilarities);

                if (alpha < alphaMin){
                    break;
                }
            }

            int tempClusterCount = clusterCount;
            int[] tempNewLabels = Arrays.copyOf(newLabels, newLabels.length);

            // merge clusters with a similarity greater than alpha
            boolean[] newCluster = new boolean[clusterCount];
            boolean[] merged = new boolean[clusterCount];
            countI = 0;
            for (int i = 0; i < binaryClusterMembership.size(); i++) {
                int countN = 0;
                for (int n = 0; n < i; n++) {
                    for (int j = 0; j < binaryClusterMembership.get(i).size(); j++) {
                        for (int k = 0; k < binaryClusterMembership.get(n).size(); k++) {
                            if (!merged[countI + j] && !merged[countN + k] &&
                                    clusterSimilarities[countI + j][countN + k] >= alpha) {
                                for (int g = 0; g < tempNewLabels.length; g++){
                                    if (tempNewLabels[g] == countI + j){
                                        tempNewLabels[g] = countN + k;
                                    }
                                }
                                tempNewLabels[countI + j] = tempNewLabels[countN + k];
                                merged[countI + j] = true;
                                newCluster[countN + k] = true;
                                tempClusterCount--;

                                for (int v = 0; v < numInstances; v++) {
                                    binaryClusterMembership.get(n).get(k)[v] +=
                                            binaryClusterMembership.get(i).get(j)[v];
                                }
                            }
                        }
                    }
                    countN += binaryClusterMembership.get(n).size();
                }
                countI += binaryClusterMembership.get(i).size();
            }

            // using the initial clusters, keep going and incrementing alpha until the number of
            // clusters is greater than k
            if (stage1) {
                if (tempClusterCount >= k) {
                    clusterCount = tempClusterCount;
                    binaryClusterMembership = removeMerged(binaryClusterMembership, merged, newCluster);
                    newLabels = relabel(tempNewLabels);
                    stage1 = false;
                } else {
                    alpha += alphaIncrement;
                }
            }
            // no longer using the initial clusters, keep going and lowering alpha to the max similarity
            // until the number of clusters is less than or equal to k or less than the minimum alpha
            else{
                if (tempClusterCount == k){
                    clusterCount = tempClusterCount;
                    binaryClusterMembership = removeMerged(binaryClusterMembership, merged, newCluster);
                    newLabels = relabel(tempNewLabels);
                    break;
                }
                else if (tempClusterCount < k){
                    break;
                }
                else{
                    clusterCount = tempClusterCount;
                    binaryClusterMembership = removeMerged(binaryClusterMembership, merged, newCluster);
                    newLabels = relabel(tempNewLabels);
                }
            }
        }

        // calculate how certain each cluster is for each case
        double[][] membershipSimilarities = new double[clusterCount][];
        int clusterIdx = 0;
        for (ArrayList<double[]> clusterGroup : binaryClusterMembership) {
            for (double[] cluster : clusterGroup) {
                membershipSimilarities[clusterIdx++] = cluster;
            }
        }

        for (int i = 0; i < numInstances; i++){
            double sum = 0;
            for (int n = 0; n < clusterCount; n++){
                sum += membershipSimilarities[n][i];
            }

            for (int n = 0; n < clusterCount; n++){
                membershipSimilarities[n][i] /= sum;
            }
        }

        int[] certainClusters = new int[clusterCount];
        for (int i = 0; i < clusterCount; i++){
            for (int n = 0; n < numInstances; n++){
                if (membershipSimilarities[i][n] > alpha2){
                    certainClusters[i] = 1;
                    break;
                }
            }
        }

        // if we dont have k clusters with at least one certain (member similarity > alpha2) case, find the k most
        // certain clusters
        Integer[] clusterRanks = new Integer[clusterCount];
        if (sum(certainClusters) != k){
            double[] clusterCertainties = new double[clusterCount];
            for (int i = 0; i < clusterCount; i++) {
                int numObjects = 0;
                for (int n = 0; n < numInstances; n++) {
                    if (membershipSimilarities[i][n] > 0) {
                        clusterCertainties[i] += membershipSimilarities[i][n];
                        numObjects++;
                    }
                    clusterCertainties[i] /= numObjects;
                }
            }

            for (int i = 0; i < clusterCount; i++) {
                clusterRanks[i] = i;
            }

            GenericTools.SortIndexDescending sort = new GenericTools.SortIndexDescending(clusterCertainties);
            Arrays.sort(clusterRanks, sort);
        }
        else{
            int n = 0;
            for (int i = 0; i < clusterCount; i++) {
                if (certainClusters[i] == 1) {
                    clusterRanks[n++] = certainClusters[i];
                }
            }

            for (int i = 0; i < clusterCount; i++) {
                if (certainClusters[i] == 0) {
                    clusterRanks[n++] = certainClusters[i];
                }
            }
        }

        for (int i = 0; i < newLabels.length; i++){
            for (int n = 0; n < clusterCount; n++){
                if (newLabels[i] == clusterRanks[n]){
                    newLabels[i] = n;
                    break;
                }
            }
        }

        // calculate similarities of remaining clusters to removed ones to determine labels for new cases
        double[][] clusterSimilarities = new double[k][clusterCount - k];
        for (int i = k; i < clusterCount; i++){
            double max = -2;
            int maxIdx = -1;

            for (int n = 0; n < k; n++){
                double similarity = setCorrelation(membershipSimilarities[clusterRanks[i]],
                        membershipSimilarities[clusterRanks[n]], numInstances);
                clusterSimilarities[n][i - k] = similarity;

                if (similarity > max){
                    max = similarity;
                    maxIdx = n;
                }
            }

            for (int n = 0; n < newLabels.length; n++){
                if (newLabels[n] == i){
                    newLabels[n] = maxIdx;
                }
            }
        }

        // assign clusters to any cases which have no similarity with any of the current clusters
        assignments = new double[numInstances];
        for (int i = 0; i < numInstances; i++) {
            double max = 0;
            int maxIdx = -1;
            double sum = 0;

            for (int n = 0; n < k; n++) {
                sum += membershipSimilarities[clusterRanks[n]][i];

                if (membershipSimilarities[clusterRanks[n]][i] > max){
                    max = membershipSimilarities[clusterRanks[n]][i];
                    maxIdx = n;
                }
            }

            if (sum == 0){
                for (int n = 0; n < k; n++) {
                    for (int j = k; j < clusterCount; j++) {
                        if (membershipSimilarities[clusterRanks[j]][i] > 0) {
                            membershipSimilarities[n][i] += clusterSimilarities[n][j - k] *
                                    membershipSimilarities[clusterRanks[j]][i];
                        }
                    }

                    if (membershipSimilarities[n][i] > max){
                        max =  membershipSimilarities[n][i];
                        maxIdx = n;
                    }
                }
            }

            if (max > alpha2){
                assignments[i] = maxIdx;
            }
            else{

            }
        }
    }

    private double setCorrelation(double[] c1, double[] c2, int n){
        double c1Size = 0;
        double c2Size = 0;
        double intersection = 0;

        for (int i = 0; i < n; i ++){
            if (c1[i] > 0) {
                c1Size++;

                if (c2[i] > 0) {
                    c2Size++;
                    intersection++;
                }
            }
            else if (c2[i] > 0) {
                c2Size++;
            }
        }

        double multSize = c1Size * c2Size;

        double numerator = intersection - multSize / n;
        double denominator = Math.sqrt(multSize * (1 - c1Size / n) * (1 - c2Size / n));

        return numerator/denominator;
    }

    private double maxSimilarity(double[][] clusterSimilarities){
        double max = -1;
        for (double[] clusterSimilarity : clusterSimilarities) {
            for (double v : clusterSimilarity) {
                if (v > max) {
                    max = v;
                }
            }
        }
        return max;
    }

    private ArrayList<ArrayList<double[]>> removeMerged(ArrayList<ArrayList<double[]>> binaryClusterMembership,
                                                        boolean[] merged, boolean[] newCluster){
        ArrayList<ArrayList<double[]>> newBinaryClusterMembership = new ArrayList<>();
        int i = 0;

        for (ArrayList<double[]> clusterGroup : binaryClusterMembership) {
            ArrayList<double[]> newGroup = new ArrayList<>();

            for (int j = 0; j < clusterGroup.size(); j++) {
                if (newCluster[i]) {
                    if (clusterGroup.size() > 1) {
                        ArrayList<double[]> newSingleGroup = new ArrayList<>(1);
                        newSingleGroup.add(clusterGroup.get(j));
                        newBinaryClusterMembership.add(newSingleGroup);
                    } else {
                        newBinaryClusterMembership.add(clusterGroup);
                    }
                } else if (!merged[i]) {
                    newGroup.add(clusterGroup.get(j));
                }

                i++;
            }

            if (newGroup.size() > 0) {
                newBinaryClusterMembership.add(newGroup);
            }
        }

        return newBinaryClusterMembership;
    }

    private int[] relabel(int[] labels){
        Integer[] unique = unique(labels).toArray(new Integer[0]);
        for (int i = 0; i < labels.length; i++){
            for (int n = 0; n < unique.length; n++){
                if (labels[i] == unique[n]){
                    labels[i] = n;
                    break;
                }
            }
        }
        return labels;
    }

    public static void main(String[] args) throws Exception {
        String dataset = "Trace";
        Instances inst = DatasetLoading.loadDataNullable("D:\\CMP Machine Learning\\Datasets\\UnivariateARFF\\" + dataset + "/" +
                dataset + "_TRAIN.arff");
        Instances inst2 = DatasetLoading.loadDataNullable("D:\\CMP Machine Learning\\Datasets\\UnivariateARFF\\" + dataset + "/" +
                dataset + "_TEST.arff");
        inst.setClassIndex(inst.numAttributes() - 1);
        inst.addAll(inst2);

        ArrayList<EnhancedAbstractClusterer> clusterers = new ArrayList<>();
        for (int i = 0; i < 3; i++){
            KMeans c = new KMeans();
            c.setNumClusters(inst.numClasses());
            c.setSeed(i);
            clusterers.add(c);
        }

        ACE k = new ACE(clusterers);
        k.setNumClusters(3);
        k.setSeed(0);
        k.buildClusterer(inst);

//        System.out.println(k.clusters.length);
//        System.out.println(Arrays.toString(k.assignments));
//        System.out.println(Arrays.toString(k.clusters));
//        System.out.println(randIndex(k.assignments, inst));
    }
}