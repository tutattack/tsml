package ml_6002b_coursework;

import scala.Int;

import java.util.Arrays;

/**
 * Empty class for Part 2.1 of the coursework.
 */
public class AttributeMeasures {

    /**
     * Main method.
     *
     * @param args the options for the attribute measure main
     */
    public static void main(String[] args) {

        Integer[][] Peaty = new Integer[][]{{4,0},{1,5}};
        System.out.println("Measure Information Gain for Peaty ="+measureInformationGain(Peaty));
        System.out.println("Measure Information Gain Ratio for Peaty ="+measureInformationGainRatio(Peaty));
        System.out.println("Measure Gini for Peaty ="+measureGini(Peaty));
        System.out.println("Measure Chi Squared for Peaty ="+measureChiSquared(Peaty));
    }

    public static double[] findRowTotals(Integer[][] array){
        double[] rowTotal = new double[array.length];
        for(int i  = 0; i< array.length; i++){
            for(int j = 0; j < array[i].length; j++){
                rowTotal[i] += array[i][j];
            }
        }

        return rowTotal;
    }

    public static Integer[] findColumnTotals(Integer[][] array){
        Integer[] columnTotal = new Integer[array.length];
        Arrays.fill(columnTotal, 0);

        for(int i  = 0; i< array.length; i++){
            for(int j = 0; j < array[i].length; j++){
                columnTotal[j] += array[i][j];
            }
        }

        return columnTotal;
    }

    public static double findTableTotal(Integer[][] array){
        double tableTotal = 0;
        double[] rowTotals = findRowTotals(array);

        for (double row: rowTotals){
            tableTotal += row;
        }

        return tableTotal;
    }

    public static double findTableTotal(double[] rowTotals){
        double tableTotal = 0;

        for (double row: rowTotals){
            tableTotal += row;
        }

        return tableTotal;
    }

    /*
    Method for Finding the Entropy of the root from a contingency table
     */
    public static double findRootEntropy(Integer[][] array){
        double rootEntropy = 0;
        Integer[] columnTotal = findColumnTotals(array);
        double[] rowTotal = findRowTotals(array);
        double tableTotal = findTableTotal(rowTotal);

        for (Integer d:columnTotal){
            tableTotal += d;
        }

        for (Integer d:columnTotal){
            rootEntropy += d/tableTotal * (Math.log(d/tableTotal)/ Math.log(2));
        }
        rootEntropy = rootEntropy * -1;

        //System.out.println(rootEntropy);


        return rootEntropy;
    }

    public static double measureInformationGain(Integer[][] array){
        double rootEntropy= findRootEntropy(array);
        double[] rowEntropys = new double[array.length];
        double gain = 0;

        double[] rowTotal = findRowTotals(array);
        double tableTotal = findTableTotal(rowTotal);


        for (int i = 0; i < array.length; i++){
            for (int j = 0; j < array[i].length; j++){
                if(array[i][j] != 0){
                    rowEntropys[i] += array[i][j]/rowTotal[i] * (Math.log(array[i][j]/rowTotal[i]) / Math.log(2));
                }
            }
            rowEntropys[i] = rowEntropys[i] * -1;
        }

        //System.out.println(Arrays.toString(rowEntropys));

        for (int i = 0; i < rowEntropys.length; i++){
            gain += (rowTotal[i]/tableTotal) * rowEntropys[i];
        }

        gain = rootEntropy - gain;
        //System.out.println(gain);
        return gain;
    }

    public static double measureInformationGainRatio(Integer[][] array){
        double splitInfo = 0;
        double gainRatio = measureInformationGain(array);

        double[] rowTotal = findRowTotals(array);
        double tableTotal = findTableTotal(rowTotal);


        for (double row : rowTotal) {
            splitInfo += (row / tableTotal) * (Math.log(row / tableTotal) / Math.log(2));
        }
        splitInfo = splitInfo * -1;
        //System.out.println(splitInfo);

        gainRatio = gainRatio / splitInfo;
        //System.out.println(gainRatio);
        return gainRatio;
    }

    //Do not need to protect if a 0 is passed through
    // 0^2 = 0

    public static double giniImpurity(Integer[] row, double rowTotal){
        double impurity = 0;

        for (double num: row){
            impurity += Math.pow((num / rowTotal),2);
        }

        impurity = 1 - impurity;

        return impurity;
    }

    public static double measureGini(Integer[][] array){
        double[] rowImpurity = new double[array.length];
        double[] rowTotal = findRowTotals(array);
        double tableTotal = findTableTotal(rowTotal);
        double gini = giniImpurity(findColumnTotals(array), tableTotal);  //Gets root impurity

        for (int i = 0; i < array.length; i++){
            rowImpurity[i] = giniImpurity(array[i], rowTotal[i]);
        }

        //System.out.println(Arrays.toString(rowImpurity));

        for (int i = 0; i < array.length; i++){
            gini -= (rowTotal[i]/tableTotal) * rowImpurity[i];
        }

        //System.out.println(gini);

        return gini;

    }

    public static double measureChiSquared(Integer[][] array){
        double[] rowTotals = findRowTotals(array);
        double tableTotal = findTableTotal(rowTotals);
        Integer[] columnTotals = findColumnTotals(array);


        double[] globalProbs = new double[columnTotals.length];
        double[][] expected = new double[rowTotals.length][columnTotals.length];

        double chi = 0;

        for (int i = 0; i < columnTotals.length; i++){
            globalProbs[i] = columnTotals[i] / tableTotal;
        }

        for (int i = 0; i < rowTotals.length; i++){
            for (int j = 0; j < columnTotals.length; j++) {
                expected[i][j] = rowTotals[i] * globalProbs[j];
            }
        }


        for (int i = 0; i < rowTotals.length; i++){
            for (int j = 0; j < columnTotals.length; j++){
                chi += (Math.pow((array[i][j] - expected[i][j]),2)) / expected[i][j];
            }
        }

        return chi;

    }
}
