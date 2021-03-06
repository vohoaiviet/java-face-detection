package com.cs.comp7502.classifier;

import com.cs.comp7502.training.Adaboost;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class CascadedClassifier implements JSONRW {

    private ArrayList<Stage> stages = new ArrayList<Stage>();

    public ArrayList<Stage> getStages() {
        return stages;
    }

    // Viola-Jones Cascade Classifier
    // 1. set following params
    //      maxFPR, the maximum acceptable false positive rate per layer (stage)
    //      minDR, the minimum acceptable detection rate per layer (stage)
    //      targetFPR, the target false positive rate for cascade classifier
    //      posSet, set of positive samples
    //      negSet, set of negative samples
    public static CascadedClassifier train(List<Feature> possibleFeatures, double maxFPR, double minDR, double targetFPR, List<File> faces, List<File> nonFaces) {
        // 2. initialise following params
        //      FPR = 0.0, the false positive rate we get for current cascade classifier (part of the final one)
        //      DR = 0.0, the detection rate we get for current cascade classifier (part of the final one)
        //      i = 0, the layer (stage) index

        double fPR = 1.0;
        double dR = 1.0;

        List<File> P = faces;
        List<File> N = nonFaces;

        // 3. train the cascade classifier
        //      while (FPR > targetFPR) {
        int layer = 1;
        CascadedClassifier cascadedClassifier = new CascadedClassifier();
        System.out.println("----Starting training----");
        while (fPR > targetFPR) {
            int maxClassifiers = Math.min(10*layer + 10, 200);

            int n = 0; // the size of feature set
            System.out.println("----Computing stage " +  layer + "----");
            long stageTime = System.currentTimeMillis();
            double newFPR = fPR;
            double newDR = dR;

            Stage stage = null;
            boolean retry = false;
            while (newFPR > maxFPR * fPR) {
                n++;
                if (n > maxClassifiers) {
                    retry = true;
                    break;
                }
                int subIndex = ThreadLocalRandom.current().nextInt(0, possibleFeatures.size() - n);
                stage = Adaboost.learn(possibleFeatures.subList(subIndex, subIndex + n), P, N);

                double threshold = stage.getStageThreshold();
                double originalThreshold = threshold;
                double decrement = Math.abs(originalThreshold) * 0.02;
                boolean discard;
                int count = 0;
                do {
                    discard = (Double.isNaN(threshold) || Double.isNaN(decrement) || Double.isInfinite(decrement) || Double.isInfinite(threshold));
                    if (count > 10000) discard = true; // prevent infinite loop // TODO check why it can go < T - |T|
                    if (discard) break;
                    //decrease the stage threshold for this adaboost classifier
                    stage.setStageThreshold(threshold);
                    threshold -= decrement;
                    // (evaluate the cascaded classifier on the training set)
                    double[] results = cascadedClassifier.evaluate(stage, faces, nonFaces);
                    newDR = results[0];
                    newFPR = results[1];
                    count++;

                } while (newDR < minDR * dR);
                System.out.println("----Computed stage " + layer + ", classifier " + n + " newFPR " + newFPR + " maxFPR * fPR " + maxFPR * fPR +" newDR " + newDR + " dR " + dR + " orginalThreshold " + originalThreshold + " finalThreshold " + stage.getStageThreshold() + " ----");
                if (discard) {
                    retry = true;
                    System.out.println("----Computed stage " + layer + ", discard classifier, decrement " + decrement + "----");
                    break;
                }
            }
            if (retry) {
                System.out.println("retrying...");
                continue;
            }

            if (stage == null) throw new RuntimeException("stage is null");
            cascadedClassifier.add(stage);
            System.out.println("----Finished computing stage " +  layer + " in" + ((System.currentTimeMillis() - stageTime)/1000) +"s ----");
            System.out.println("----Number of classifiers in stage " +  layer + " is " + stage.getClassifierList().size() + "----");

            fPR = newFPR;
            dR = newDR;
            //  clear negSet
            N = new ArrayList<>();

            if (fPR > targetFPR) {
                // give a set of negative sample
                for (File nonFace : nonFaces){
                    // for any negative sample which can be detected as face
                    // put it into negSet
                    boolean isFace = cascadedClassifier.isFace(nonFace);
                    if (isFace) N.add(nonFace);
                }
            }
            layer++;
        }
        return cascadedClassifier;
    }

    private void add(Stage stage) {
        stages.add(stage);
    }

    public boolean isFace(File image) {
        for (Stage stage: stages) {
            if (!stage.isFace(image)) return false;
        }
        return true;
    }

    public boolean isFace(int[][] image) {
        for (Stage stage: stages) {
            if (!stage.isFace(image)) return false;
        }
        return true;
    }

    public double[] evaluate(Stage stage, List<File> faces, List<File> nonfaces) {
        int faceNum = faces.size();
        int nonFaceNum = nonfaces.size();

        int posFaceNum = 0;
        int negNonFaceNum = 0;

        for (File face : faces) {
            boolean isFace = this.isFace(face);
            if (isFace) {
                isFace = stage.isFace(face);
                if (isFace)
                    posFaceNum++;
            }
        }

        for (File nonFace : nonfaces) {
            boolean isFace = this.isFace(nonFace);
            if (isFace) {
                isFace = stage.isFace(nonFace);
                if (isFace)
                    negNonFaceNum++;
            }
        }

        return new double[]{(double) posFaceNum / faceNum, (double) negNonFaceNum / nonFaceNum};
    }

    @Override
    public JSONObject encode() {
        JSONObject cascadedClassifier = new JSONObject();
        JSONArray stages = new JSONArray();
        int stageNum = 0;
        try {
            ArrayList<Stage> stageList = getStages();
            stageNum = stageList.size();
            for (Stage stage : stageList) {
                JSONObject stageJSON = stage.encode();
                stages.put(stageJSON);
            }
            cascadedClassifier.put("stage#", stageNum);
            cascadedClassifier.put("stages", stages);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return cascadedClassifier;
    }

    @Override
    public void decode(JSONObject json) {
        try {
            JSONArray stages = json.getJSONArray("stages");
            for (int i = 0 ; i <stages.length(); i++){
                JSONObject jsonObject = stages.getJSONObject(i);
                Stage stage = new Stage();
                stage.decode(jsonObject);
                this.stages.add(stage);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}
