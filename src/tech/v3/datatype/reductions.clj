(ns tech.v3.datatype.reductions
  (:require [tech.v3.datatype.base :as dtype-base]
            [tech.v3.parallel.for :as parallel-for]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.protocols :as dtype-proto]
            [primitive-math :as pmath])
  (:import [tech.v3.datatype BinaryOperator IndexReduction DoubleReduction
            PrimitiveIO IndexReduction$IndexedBiFunction]
           [java.util List Map HashMap Map$Entry]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.function BiFunction BiConsumer]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defn commutative-binary-double
  ^double [^BinaryOperator op rdr]
  (let [rdr (dtype-base/->reader rdr)]
    (double
     (parallel-for/indexed-map-reduce
      (.lsize rdr)
      (fn [^long start-idx ^long group-len]
        (let [end-idx (+ start-idx group-len)]
          (loop [idx (inc start-idx)
                 accum (.readDouble rdr start-idx)]
            (if (< idx end-idx)
              (recur (unchecked-inc idx) (.binaryDouble
                                          op accum
                                          (.readDouble rdr idx)))
              accum))))
      (partial reduce op)))))


(defn commutative-binary-long
  ^long [^BinaryOperator op rdr]
  (let [rdr (dtype-base/->reader rdr)]
    (long
     (parallel-for/indexed-map-reduce
      (.lsize rdr)
      (fn [^long start-idx ^long group-len]
        (let [end-idx (+ start-idx group-len)]
          (loop [idx (inc start-idx)
                 accum (.readLong rdr start-idx)]
            (if (< idx end-idx)
              (recur (unchecked-inc idx) (.binaryLong
                                          op accum
                                          (.readLong rdr idx)))
              accum))))
      (partial reduce op)))))


(defn commutative-binary-object
  [op rdr]
  (let [rdr (dtype-base/->reader rdr)]
    (parallel-for/indexed-map-reduce
     (.lsize rdr)
     (fn [^long start-idx ^long group-len]
       (let [end-idx (+ start-idx group-len)]
         (loop [idx (inc start-idx)
                accum (.readObject rdr start-idx)]
           (if (< idx end-idx)
             (recur (unchecked-inc idx) (op accum
                                         (.readObject rdr idx)))
             accum))))
     (partial reduce op))))


(defn commutative-binary-reduce
  [op rdr]
  (if-let [rdr (dtype-base/->reader rdr)]
    (if (instance? BinaryOperator op)
      (let [rdr-dtype (dtype-base/elemwise-datatype rdr)]
        (cond
          (casting/integer-type? rdr-dtype)
          (commutative-binary-long op rdr)
          (casting/float-type? rdr-dtype)
          (commutative-binary-double op rdr)
          :else
          (commutative-binary-object op rdr)))
      (commutative-binary-object op rdr))
    ;;Clojure core reduce is actually pretty good!
    (reduce op rdr)))


(defn indexed-reduction
  ([^IndexReduction reducer rdr finalize?]
   (let [rdr (dtype-base/->reader rdr)
         n-elems (.lsize rdr)
         batch-data (.prepareBatch reducer rdr)
         retval
         (parallel-for/indexed-map-reduce
          (.lsize rdr)
          (fn [^long start-idx ^long group-len]
            (let [end-idx (+ start-idx group-len)]
              (loop [idx start-idx
                     ctx nil]
                (if (< idx end-idx)
                  (recur (unchecked-inc idx)
                         (.reduceIndex reducer batch-data ctx idx))
                  ctx))))
          (partial reduce (fn [lhs-ctx rhs-ctx]
                            (.reduceReductions reducer lhs-ctx rhs-ctx))))]
     (if finalize?
       (.finalize reducer retval n-elems)
       retval)))
  ([^IndexReduction reducer rdr]
   (indexed-reduction reducer rdr true)))


(defn double-reducers->indexed-reduction
  "Make an index reduction out of a map of reducer-name to reducer.  Stores intermediate values
  in double arrays.  Upon finalize, returns a map of reducer-name to finalized double reduction
  value."
  ^IndexReduction [reducer-map]
  (let [^List reducer-names (keys reducer-map)
        reducers (object-array (vals reducer-map))
        n-reducers (alength reducers)]
    (reify IndexReduction
      (reduceIndex [this batch-data ctx idx]
        (let [dval (.readDouble ^PrimitiveIO batch-data idx)]
          (if-not ctx
            (let [ctx (double-array n-reducers)]
              (dotimes [reducer-idx n-reducers]
                (aset ctx reducer-idx
                      (.elemwise ^DoubleReduction (aget reducers reducer-idx)
                                 dval)))
              ctx)
            (let [^doubles ctx ctx]
              (dotimes [reducer-idx n-reducers]
                (aset ctx reducer-idx
                      (.update
                       ^DoubleReduction (aget reducers reducer-idx)
                       (aget ctx reducer-idx)
                       (.elemwise ^DoubleReduction (aget reducers reducer-idx)
                                  dval))))
              ctx))))
      (reduceReductions [this lhs-ctx rhs-ctx]
        (let [^doubles lhs-ctx lhs-ctx
              ^doubles rhs-ctx rhs-ctx]
          (dotimes [reducer-idx n-reducers]
            (aset lhs-ctx reducer-idx
                  (.merge ^DoubleReduction (aget reducers reducer-idx)
                          (aget lhs-ctx reducer-idx)
                          (aget rhs-ctx reducer-idx))))
          lhs-ctx))
      (finalize [this ctx n-elems]
        (->> (map (fn [k r v]
                    [k (.finalize ^DoubleReduction r v n-elems)])
                  reducer-names reducers ctx)
             (into {}))))))


(defn double-reductions
  "Given a map of name->reducer of DoubleReduction implementations and a rdr
  do an efficient two-level parallelized reduction and return the results in
  a map of name->finalized-result."
  [reducer-map rdr]
  (-> (double-reducers->indexed-reduction reducer-map)
      (indexed-reduction rdr)))


(defn unordered-group-by-reduce
  "Perform an unordered group-by operation using reader and placing results
  into the result-map.  Expects that reducer's batch-data method has already been
  called and Returns the non-finalized result-map.
  If a map is passed in then it's compute operator needs to be threadsafe.
  If result-map is nil then one is created.
  This implementation takes advantage of the fact that for java8+, we have essentially a lock
  free concurrent hash map as long as there aren't collisions so it performs surprisingly well
  considering the amount of pressure this can put on the concurrency model."
  (^Map [^IndexReduction reducer batch-data rdr ^Map result-map]
   (let [^Map result-map (or result-map (ConcurrentHashMap.))
         bifn (IndexReduction$IndexedBiFunction. reducer batch-data)
         rdr (dtype-base/->reader rdr)
         n-elems (.lsize rdr)]
     ;;Side effecting loop to compute values in-place
     (parallel-for/parallel-for
      idx
      n-elems
      (do
        ;;java.util.Map compute really should take more arguments but this way
        ;;the long is never boxed.
        (.setIndex bifn idx)
        (.compute result-map (.readObject rdr idx) bifn)))
     result-map))
  (^Map [reducer batch-data rdr]
   (unordered-group-by-reduce reducer batch-data rdr nil)))



(defn ordered-group-by-reduce
  "Perform an ordered group-by operation using reader and placing results
  into the result-map.  Expects that reducer's batch-data method has already been
  called and Returns the non-finalized result-map.
  If result-map is nil then one is created.
  Each bucket's results end up ordered by index iteration order.  The original parallel pass
  goes through each index in order and then the reduction goes through the thread groups in order
  so if your index reduction merger just does (.addAll lhs rhs) then the final result ends up
  ordered."
  (^Map [^IndexReduction reducer batch-data rdr]
   (let [bifn (IndexReduction$IndexedBiFunction. reducer batch-data)
         rdr (dtype-base/->reader rdr)
         n-elems (.lsize rdr)
         merge-bifn (reify BiFunction
                      (apply [this lhs rhs]
                        (.reduceReductions reducer lhs rhs)))]
     ;;Side effecting loop to compute values in-place
     (parallel-for/indexed-map-reduce
      n-elems
      (fn [^long start-idx ^long group-len]
        (let [result-map (HashMap.)
              end-idx (+ start-idx group-len)]
          (loop [idx start-idx]
            (when (< idx end-idx)
              (.setIndex bifn idx)
              (.compute result-map (.readObject rdr idx) bifn)
              (recur (unchecked-inc idx))))
          result-map))
      (partial reduce (fn [^Map lhs-map ^Map rhs-map]
                        (.forEach rhs-map
                                  (reify BiConsumer
                                    (accept [this k v]
                                      (.merge lhs-map k v merge-bifn))))
                        lhs-map))))))
