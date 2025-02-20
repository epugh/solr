/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.client.solrj.io.stream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.math3.distribution.IntegerDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.apache.commons.math3.ml.clustering.CentroidCluster;
import org.apache.commons.math3.ml.clustering.Cluster;
import org.apache.commons.math3.random.EmpiricalDistribution;
import org.apache.commons.math3.stat.Frequency;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.apache.commons.math3.util.Precision;
import org.apache.solr.client.solrj.io.Tuple;
import org.apache.solr.client.solrj.io.comp.StreamComparator;
import org.apache.solr.client.solrj.io.eval.DbscanEvaluator;
import org.apache.solr.client.solrj.io.eval.KmeansEvaluator;
import org.apache.solr.client.solrj.io.eval.Matrix;
import org.apache.solr.client.solrj.io.eval.StreamEvaluator;
import org.apache.solr.client.solrj.io.stream.expr.Explanation;
import org.apache.solr.client.solrj.io.stream.expr.Explanation.ExpressionType;
import org.apache.solr.client.solrj.io.stream.expr.Expressible;
import org.apache.solr.client.solrj.io.stream.expr.StreamExplanation;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpression;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionNamedParameter;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionParameter;
import org.apache.solr.client.solrj.io.stream.expr.StreamExpressionValue;
import org.apache.solr.client.solrj.io.stream.expr.StreamFactory;

public class ZplotStream extends TupleStream implements Expressible {

  private static final long serialVersionUID = 1;
  private StreamContext streamContext;

  @SuppressWarnings({"rawtypes"})
  private Map<String, Object> letParams = new LinkedHashMap<>();

  private Iterator<Tuple> out;

  public ZplotStream(StreamExpression expression, StreamFactory factory) throws IOException {

    List<StreamExpressionNamedParameter> namedParams = factory.getNamedOperands(expression);
    // Get all the named params

    for (StreamExpressionNamedParameter np : namedParams) {
      String name = np.getName();
      StreamExpressionParameter param = np.getParameter();
      if (param instanceof StreamExpressionValue) {
        String paramValue = ((StreamExpressionValue) param).getValue();
        letParams.put(name, factory.constructPrimitiveObject(paramValue));
      } else if (factory.isEvaluator((StreamExpression) param)) {
        StreamEvaluator evaluator = factory.constructEvaluator((StreamExpression) param);
        letParams.put(name, evaluator);
      }
    }
  }

  @Override
  public StreamExpression toExpression(StreamFactory factory) throws IOException {
    return toExpression(factory, true);
  }

  private StreamExpression toExpression(StreamFactory factory, boolean includeStreams)
      throws IOException {
    // function name
    StreamExpression expression = new StreamExpression(factory.getFunctionName(this.getClass()));

    return expression;
  }

  @Override
  public Explanation toExplanation(StreamFactory factory) throws IOException {

    StreamExplanation explanation = new StreamExplanation(getStreamNodeId().toString());
    explanation.setFunctionName(factory.getFunctionName(this.getClass()));
    explanation.setImplementingClass(this.getClass().getName());
    explanation.setExpressionType(ExpressionType.STREAM_DECORATOR);
    explanation.setExpression(toExpression(factory, false).toString());

    return explanation;
  }

  @Override
  public void setStreamContext(StreamContext context) {
    this.streamContext = context;
  }

  @Override
  public List<TupleStream> children() {
    List<TupleStream> l = new ArrayList<>();
    return l;
  }

  @Override
  public Tuple read() throws IOException {
    if (out.hasNext()) {
      return out.next();
    } else {
      return Tuple.EOF();
    }
  }

  @Override
  public void close() throws IOException {}

  @Override
  public void open() throws IOException {
    Map<String, Object> lets = streamContext.getLets();
    Set<Map.Entry<String, Object>> entries = letParams.entrySet();
    Map<String, Object> evaluated = new HashMap<>();

    // Load up the StreamContext with the data created by the letParams.
    int numTuples = -1;
    int columns = 0;
    boolean table = false;
    boolean distribution = false;
    boolean clusters = false;
    boolean heat = false;
    for (Map.Entry<String, Object> entry : entries) {
      ++columns;

      String name = entry.getKey();
      if (name.equals("table")) {
        table = true;
      } else if (name.equals("dist")) {
        distribution = true;
      } else if (name.equals("clusters")) {
        clusters = true;
      } else if (name.equals("heat")) {
        heat = true;
      }

      Object o = entry.getValue();
      if (o instanceof StreamEvaluator evaluator) {
        Tuple eTuple = new Tuple(lets);
        evaluator.setStreamContext(streamContext);
        Object eo = evaluator.evaluate(eTuple);
        if (eo instanceof List<?> l) {
          if (numTuples == -1) {
            numTuples = l.size();
          } else {
            if (l.size() != numTuples) {
              throw new IOException(
                  "All lists provided to the zplot function must be the same length.");
            }
          }
          evaluated.put(name, l);
        } else if (eo instanceof Tuple) {
          evaluated.put(name, eo);
        } else {
          evaluated.put(name, eo);
        }
      } else {
        Object eval = lets.get(o);
        if (eval instanceof List<?> l) {
          if (numTuples == -1) {
            numTuples = l.size();
          } else {
            if (l.size() != numTuples) {
              throw new IOException(
                  "All lists provided to the zplot function must be the same length.");
            }
          }
          evaluated.put(name, l);
        } else if (eval instanceof Tuple) {
          evaluated.put(name, eval);
        } else if (eval instanceof Matrix) {
          evaluated.put(name, eval);
        }
      }
    }

    if (columns > 1 && (table || distribution)) {
      throw new IOException(
          "If the table or dist parameter is set there can only be one parameter.");
    }
    // Load the values into tuples

    List<Tuple> outTuples = new ArrayList<>();
    if (!table && !distribution && !clusters && !heat) {
      // Handle the vectors
      for (int i = 0; i < numTuples; i++) {
        Tuple tuple = new Tuple();
        for (Map.Entry<String, Object> entry : evaluated.entrySet()) {
          List<?> l = (List<?>) entry.getValue();
          tuple.put(entry.getKey(), l.get(i));
        }

        outTuples.add(tuple);
      }

      // Generate the x-axis if the tuples contain y and not x
      if (outTuples.get(0).getFields().containsKey("y")
          && !outTuples.get(0).getFields().containsKey("x")) {
        int x = 0;
        for (Tuple tuple : outTuples) {
          tuple.put("x", x++);
        }
      }
    } else if (clusters) {
      Object o = evaluated.get("clusters");
      if (o instanceof KmeansEvaluator.ClusterTuple ct) {
        List<CentroidCluster<KmeansEvaluator.ClusterPoint>> cs = ct.getClusters();
        int clusterNum = 0;
        for (CentroidCluster<KmeansEvaluator.ClusterPoint> c : cs) {
          clusterNum++;
          List<KmeansEvaluator.ClusterPoint> points = c.getPoints();
          for (KmeansEvaluator.ClusterPoint p : points) {
            Tuple tuple = new Tuple();
            tuple.put("x", p.getPoint()[0]);
            tuple.put("y", p.getPoint()[1]);
            tuple.put("cluster", "cluster" + clusterNum);
            outTuples.add(tuple);
          }
        }
      } else if (o instanceof DbscanEvaluator.ClusterTuple ct) {
        List<Cluster<DbscanEvaluator.ClusterPoint>> cs = ct.getClusters();
        int clusterNum = 0;
        for (Cluster<DbscanEvaluator.ClusterPoint> c : cs) {
          clusterNum++;
          List<DbscanEvaluator.ClusterPoint> points = c.getPoints();
          for (DbscanEvaluator.ClusterPoint p : points) {
            Tuple tuple = new Tuple();
            tuple.put("x", p.getPoint()[0]);
            tuple.put("y", p.getPoint()[1]);
            tuple.put("cluster", "cluster" + clusterNum);
            outTuples.add(tuple);
          }
        }
      }
    } else if (distribution) {
      Object o = evaluated.get("dist");
      if (o instanceof RealDistribution realDistribution) {
        List<SummaryStatistics> binStats = null;
        if (realDistribution instanceof EmpiricalDistribution empiricalDistribution) {
          binStats = empiricalDistribution.getBinStats();
        } else {
          double[] samples = realDistribution.sample(500000);
          EmpiricalDistribution empiricalDistribution = new EmpiricalDistribution(32);
          empiricalDistribution.load(samples);
          binStats = empiricalDistribution.getBinStats();
        }
        double[] x = new double[binStats.size()];
        double[] y = new double[binStats.size()];
        for (int i = 0; i < binStats.size(); i++) {
          x[i] = binStats.get(i).getMean();
          y[i] = realDistribution.density(x[i]);
        }

        for (int i = 0; i < x.length; i++) {
          Tuple tuple = new Tuple();
          if (!Double.isNaN(x[i])) {
            tuple.put("x", Precision.round(x[i], 2));
            if (y[i] == Double.NEGATIVE_INFINITY || y[i] == Double.POSITIVE_INFINITY) {
              tuple.put("y", 0);

            } else {
              tuple.put("y", y[i]);
            }
            outTuples.add(tuple);
          }
        }
      } else if (o instanceof IntegerDistribution integerDistribution) {
        int[] samples = integerDistribution.sample(50000);
        Frequency frequency = new Frequency();
        for (int i : samples) {
          frequency.addValue(i);
        }

        Iterator<?> it = frequency.valuesIterator();
        List<Long> values = new ArrayList<>();
        while (it.hasNext()) {
          values.add((Long) it.next());
        }
        int[] x = new int[values.size()];
        double[] y = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
          x[i] = values.get(i).intValue();
          y[i] = integerDistribution.probability(x[i]);
        }

        for (int i = 0; i < x.length; i++) {
          Tuple tuple = new Tuple();
          tuple.put("x", x[i]);
          tuple.put("y", y[i]);
          outTuples.add(tuple);
        }
      } else if (o instanceof List<?> list) {
        if (list.get(0) instanceof Tuple) {
          @SuppressWarnings({"unchecked"})
          List<Tuple> tlist = (List<Tuple>) o;
          Tuple tuple = tlist.get(0);
          if (tuple.getFields().containsKey("N")) {
            for (Tuple t : tlist) {
              Tuple outtuple = new Tuple();
              outtuple.put("x", Precision.round(((double) t.get("mean")), 2));
              outtuple.put("y", t.get("prob"));
              outTuples.add(outtuple);
            }
          } else if (tuple.getFields().containsKey("count")) {
            for (Tuple t : tlist) {
              Tuple outtuple = new Tuple();
              outtuple.put("x", t.get("value"));
              outtuple.put("y", t.get("pct"));
              outTuples.add(outtuple);
            }
          }
        }
      }
    } else if (table) {
      // Handle the Tuple and List of Tuples
      Object o = evaluated.get("table");
      if (o instanceof Matrix m) {
        List<String> rowLabels = m.getRowLabels();
        List<String> colLabels = m.getColumnLabels();
        double[][] data = m.getData();
        for (int i = 0; i < data.length; i++) {
          String rowLabel = null;
          if (rowLabels != null) {
            rowLabel = rowLabels.get(i);
          } else {
            rowLabel = Integer.toString(i);
          }
          Tuple tuple = new Tuple();
          tuple.put("rowLabel", rowLabel);
          double[] row = data[i];
          for (int j = 0; j < row.length; j++) {
            String colLabel = null;
            if (colLabels != null) {
              colLabel = colLabels.get(j);
            } else {
              colLabel = "col" + Integer.toString(j);
            }

            tuple.put(colLabel, data[i][j]);
          }
          outTuples.add(tuple);
        }
      }
    } else if (heat) {
      // Handle the Tuple and List of Tuples
      Object o = evaluated.get("heat");
      if (o instanceof Matrix m) {
        List<String> rowLabels = m.getRowLabels();
        List<String> colLabels = m.getColumnLabels();
        double[][] data = m.getData();
        for (int i = 0; i < data.length; i++) {
          String rowLabel = null;
          if (rowLabels != null) {
            rowLabel = rowLabels.get(i);
          } else {
            rowLabel = "row" + pad(Integer.toString(i), data.length);
          }

          double[] row = data[i];
          for (int j = 0; j < row.length; j++) {
            Tuple tuple = new Tuple();
            tuple.put("y", rowLabel);
            String colLabel = null;
            if (colLabels != null) {
              colLabel = colLabels.get(j);
            } else {
              colLabel = "col" + pad(Integer.toString(j), row.length);
            }
            tuple.put("x", colLabel);
            tuple.put("z", data[i][j]);
            outTuples.add(tuple);
          }
        }
      }
    }

    this.out = outTuples.iterator();
  }

  public static String pad(String v, int length) {
    if (length < 11) {
      return v;
    } else if (length < 101) {
      return prepend(v, 2);
    } else if (length < 1001) {
      return prepend(v, 3);
    } else if (length < 10001) {
      return prepend(v, 4);
    } else {
      return prepend(v, 5);
    }
  }

  private static String prepend(String v, int length) {
    while (v.length() < length) {
      v = "0" + v;
    }

    return v;
  }

  /** Return the stream sort - ie, the order in which records are returned */
  @Override
  public StreamComparator getStreamSort() {
    return null;
  }

  @Override
  public int getCost() {
    return 0;
  }
}
