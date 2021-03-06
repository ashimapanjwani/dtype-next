package tech.v3.datatype;

import clojure.lang.Keyword;
import java.util.function.DoubleConsumer;
import java.util.function.DoublePredicate;
import java.util.HashMap;
import java.util.Collections;


public class DoubleConsumers
{
  public static abstract class ScalarReduceBase implements Consumers.StagedConsumer,
							   DoubleConsumer
  {
    public static final Keyword nElemsKwd = Keyword.intern(null, "n-elems");
    public double value;
    public long nElems;
    public ScalarReduceBase() {
      value = 0.0;
      nElems = 0;
    }
    public static Object valueMap(Keyword valueKwd, double value, long nElems ){
      HashMap hm = new HashMap();
      hm.put( valueKwd, value );
      hm.put( nElemsKwd, nElems );
      return hm;
    }
  }
  public static class Sum extends ScalarReduceBase
  {
    public static final Keyword valueKwd = Keyword.intern(null, "sum");
    public Sum() {
      super();
    }
    public void accept(double data) {
      value += data;
      nElems++;
    }
    public void inplaceCombine(Consumers.StagedConsumer _other) {
      ScalarReduceBase other = (ScalarReduceBase)_other;
      value += other.value;
      nElems += other.nElems;
    }
    public Object value() {
      return valueMap(valueKwd,value,nElems);
    }
  }
  public static class UnaryOpSum extends Sum
  {
    public final UnaryOperator op;
    public UnaryOpSum(UnaryOperator _op) {
      super();
      op = _op;
    }
    public void accept(double data) {
      super.accept(op.unaryDouble(data));
    }
    public Object value() {
      return valueMap(Sum.valueKwd,value,nElems);
    }
  }
  public static class BinaryOp extends ScalarReduceBase
  {
    public static final Keyword defaultValueKwd = Keyword.intern(null, "value");
    public final BinaryOperator op;
    public final Keyword valueKwd;
    public BinaryOp(BinaryOperator _op, double initValue, Keyword _valueKwd) {
      super();
      value = initValue;
      op = _op;
      if (_valueKwd == null) {
	valueKwd = defaultValueKwd;
      } else {
	valueKwd = _valueKwd;
      }
    }
    public BinaryOp(BinaryOperator _op, double initValue) {
      this(_op, initValue, null);
    }
    public void accept(double data) {
      value = op.binaryDouble(value, data);
      nElems++;
    }
    public void inplaceCombine(Consumers.StagedConsumer _other) {
      BinaryOp other = (BinaryOp)_other;
      value = op.binaryDouble(value, other.value);
      nElems += other.nElems;
    }
    public Object value() {
      return valueMap(valueKwd,value,nElems);
    }
  }
  public static class MinMaxSum implements Consumers.StagedConsumer, DoubleConsumer
  {
    public static final Keyword sumKwd = Keyword.intern(null, "sum");
    public static final Keyword minKwd = Keyword.intern(null, "min");
    public static final Keyword maxKwd = Keyword.intern(null, "max");
    public double sum;
    public double min;
    public double max;
    public long nElems;
    public MinMaxSum() {
      sum = 0.0;
      max = -Double.MAX_VALUE;
      min = Double.MAX_VALUE;
      nElems = 0;
    }
    public void accept(double val) {
      sum += val;
      min = Math.min(val, min);
      max = Math.max(val, max);
      nElems++;
    }
    double getMin() {
      if (nElems == 0)
	return Double.NaN;
      return min;
    }

    double getMax() {
      if (nElems == 0)
	return Double.NaN;
      return max;
    }

    public void inplaceCombine(Consumers.StagedConsumer _other) {
      MinMaxSum other = (MinMaxSum)_other;
      sum += other.sum;
      min = Math.min(getMin(), other.min);
      max = Math.max(getMax(), other.max);
      nElems += other.nElems;
    }
    public Object value() {
      HashMap retval = new HashMap();
      retval.put(sumKwd, sum);
      retval.put(minKwd, getMin());
      retval.put(maxKwd, getMax());
      retval.put(ScalarReduceBase.nElemsKwd, nElems);
      return retval;
    }
  }
  public static class Moments implements Consumers.StagedConsumer, DoubleConsumer
  {
    public static final Keyword m2Kwd = Keyword.intern(null, "moment-2");
    public static final Keyword m3Kwd = Keyword.intern(null, "moment-3");
    public static final Keyword m4Kwd = Keyword.intern(null, "moment-4");
    public final double mean;
    public double m2;
    public double m3;
    public double m4;
    public long nElems;
    public Moments(double _mean) {
      mean = _mean;
      m2 = 0.0;
      m3 = 0.0;
      m4 = 0.0;
      nElems = 0;
    }
    public void accept(double val) {
      double meanDiff = val - mean;
      double md2 = meanDiff * meanDiff;
      m2 += md2;
      double md3 = md2 * meanDiff;
      m3 += md3;
      double md4 = md3 * meanDiff;
      m4 += md4;
      nElems++;
    }
    public void inplaceCombine(Consumers.StagedConsumer _other) {
      Moments other = (Moments)_other;
      m2 += other.m2;
      m3 += other.m3;
      m4 += other.m4;
      nElems += other.nElems;
    }
    public Object value() {
      HashMap retval = new HashMap();
      retval.put(m2Kwd, m2);
      retval.put(m3Kwd, m3);
      retval.put(m4Kwd, m4);
      retval.put(ScalarReduceBase.nElemsKwd, nElems);
      return retval;
    }
  }
  public static Consumers.StagedConsumer
    consume(long offset, int grouplen, Buffer data,
	    Consumers.StagedConsumer consumer,
	    DoublePredicate predicate) {
    final DoubleConsumer dconsumer = (DoubleConsumer)consumer;
    if( predicate == null) {
      for (int idx = 0; idx < grouplen; ++idx) {
	dconsumer.accept(data.readDouble((long)idx + offset));
      }
    } else {
      for (int idx = 0; idx < grouplen; ++idx) {
	double dval = data.readDouble((long)idx + offset);
	if(predicate.test(dval)) {
	  dconsumer.accept(dval);
	}
      }
    }
    return consumer;
  }
}
