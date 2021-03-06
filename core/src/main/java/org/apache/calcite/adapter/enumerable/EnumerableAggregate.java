/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.adapter.enumerable.impl.AggAddContextImpl;
import org.apache.calcite.adapter.enumerable.impl.AggResultContextImpl;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.linq4j.function.Function0;
import org.apache.calcite.linq4j.function.Function1;
import org.apache.calcite.linq4j.function.Function2;
import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.ParameterExpression;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.prepare.CalcitePrepareImpl;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import com.google.common.collect.Lists;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Implementation of {@link org.apache.calcite.rel.core.Aggregate} in
 * {@link org.apache.calcite.adapter.enumerable.EnumerableConvention enumerable calling convention}. */
public class EnumerableAggregate extends Aggregate implements EnumerableRel {
  public EnumerableAggregate(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode child,
      boolean indicator,
      ImmutableBitSet groupSet,
      List<ImmutableBitSet> groupSets,
      List<AggregateCall> aggCalls)
      throws InvalidRelException {
    super(cluster, traitSet, child, indicator, groupSet, groupSets, aggCalls);
    assert getConvention() instanceof EnumerableConvention;

    for (AggregateCall aggCall : aggCalls) {
      if (aggCall.isDistinct()) {
        throw new InvalidRelException(
            "distinct aggregation not supported");
      }
      AggImplementor implementor2 =
          RexImpTable.INSTANCE.get(aggCall.getAggregation(), false);
      if (implementor2 == null) {
        throw new InvalidRelException(
            "aggregation " + aggCall.getAggregation() + " not supported");
      }
    }
  }

  @Override public EnumerableAggregate copy(RelTraitSet traitSet, RelNode input,
      boolean indicator, ImmutableBitSet groupSet,
      List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
    try {
      return new EnumerableAggregate(getCluster(), traitSet, input, indicator,
          groupSet, groupSets, aggCalls);
    } catch (InvalidRelException e) {
      // Semantic error not possible. Must be a bug. Convert to
      // internal error.
      throw new AssertionError(e);
    }
  }

  public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    final JavaTypeFactory typeFactory = implementor.getTypeFactory();
    final BlockBuilder builder = new BlockBuilder();
    final EnumerableRel child = (EnumerableRel) getInput();
    final Result result = implementor.visitChild(this, 0, child, pref);
    Expression childExp =
        builder.append(
            "child",
            result.block);
    final RelDataType inputRowType = getInput().getRowType();

    final PhysType physType =
        PhysTypeImpl.of(
            typeFactory, getRowType(), pref.preferCustom());

    // final Enumerable<Employee> child = <<child adapter>>;
    // Function1<Employee, Integer> keySelector =
    //     new Function1<Employee, Integer>() {
    //         public Integer apply(Employee a0) {
    //             return a0.deptno;
    //         }
    //     };
    // Function1<Employee, Object[]> accumulatorInitializer =
    //     new Function1<Employee, Object[]>() {
    //         public Object[] apply(Employee a0) {
    //             return new Object[] {0, 0};
    //         }
    //     };
    // Function2<Object[], Employee, Object[]> accumulatorAdder =
    //     new Function2<Object[], Employee, Object[]>() {
    //         public Object[] apply(Object[] a1, Employee a0) {
    //              a1[0] = ((Integer) a1[0]) + 1;
    //              a1[1] = ((Integer) a1[1]) + a0.salary;
    //             return a1;
    //         }
    //     };
    // Function2<Integer, Object[], Object[]> resultSelector =
    //     new Function2<Integer, Object[], Object[]>() {
    //         public Object[] apply(Integer a0, Object[] a1) {
    //             return new Object[] { a0, a1[0], a1[1] };
    //         }
    //     };
    // return childEnumerable
    //     .groupBy(
    //        keySelector, accumulatorInitializer, accumulatorAdder,
    //        resultSelector);
    //
    // or, if key has 0 columns,
    //
    // return childEnumerable
    //     .aggregate(
    //       accumulatorInitializer.apply(),
    //       accumulatorAdder,
    //       resultSelector);
    //
    // with a slightly different resultSelector; or if there are no aggregate
    // functions
    //
    // final Enumerable<Employee> child = <<child adapter>>;
    // Function1<Employee, Integer> keySelector =
    //     new Function1<Employee, Integer>() {
    //         public Integer apply(Employee a0) {
    //             return a0.deptno;
    //         }
    //     };
    // EqualityComparer<Employee> equalityComparer =
    //     new EqualityComparer<Employee>() {
    //         boolean equal(Employee a0, Employee a1) {
    //             return a0.deptno;
    //         }
    //     };
    // return child
    //     .distinct(equalityComparer);

    final PhysType inputPhysType = result.physType;

    ParameterExpression parameter =
        Expressions.parameter(inputPhysType.getJavaRowType(), "a0");

    final PhysType keyPhysType =
        inputPhysType.project(groupSet.asList(), getGroupType() != Group.SIMPLE,
            JavaRowFormat.LIST);
    final int groupCount = getGroupCount();
    final int indicatorCount = getIndicatorCount();

    final List<AggImpState> aggs =
        new ArrayList<AggImpState>(aggCalls.size());

    for (int i = 0; i < aggCalls.size(); i++) {
      AggregateCall call = aggCalls.get(i);
      aggs.add(new AggImpState(i, call, false));
    }

    // Function0<Object[]> accumulatorInitializer =
    //     new Function0<Object[]>() {
    //         public Object[] apply() {
    //             return new Object[] {0, 0};
    //         }
    //     };
    final List<Expression> initExpressions =
        new ArrayList<Expression>();
    final BlockBuilder initBlock = new BlockBuilder();

    final List<Type> aggStateTypes = new ArrayList<Type>();
    for (final AggImpState agg : aggs) {
      agg.context =
          new AggContext() {
            public SqlAggFunction aggregation() {
              return agg.call.getAggregation();
            }

            public RelDataType returnRelType() {
              return agg.call.type;
            }

            public Type returnType() {
              return EnumUtils.javaClass(typeFactory, returnRelType());
            }

            public List<? extends RelDataType> parameterRelTypes() {
              return EnumUtils.fieldRowTypes(inputRowType, null,
                  agg.call.getArgList());
            }

            public List<? extends Type> parameterTypes() {
              return EnumUtils.fieldTypes(typeFactory,
                  parameterRelTypes());
            }
          };
      List<Type> state =
          agg.implementor.getStateType(agg.context);

      if (state.isEmpty()) {
        continue;
      }

      aggStateTypes.addAll(state);

      final List<Expression> decls =
          new ArrayList<Expression>(state.size());
      for (int i = 0; i < state.size(); i++) {
        String aggName = "a" + agg.aggIdx;
        if (CalcitePrepareImpl.DEBUG) {
          aggName = Util.toJavaId(agg.call.getAggregation().getName(), 0)
              .substring("ID$0$".length()) + aggName;
        }
        Type type = state.get(i);
        ParameterExpression pe =
            Expressions.parameter(type,
                initBlock.newName(aggName + "s" + i));
        initBlock.add(Expressions.declare(0, pe, null));
        decls.add(pe);
      }
      agg.state = decls;
      initExpressions.addAll(decls);
      agg.implementor.implementReset(agg.context,
          new AggResultContextImpl(initBlock, decls));
    }

    final PhysType accPhysType =
        PhysTypeImpl.of(
            typeFactory,
            typeFactory.createSyntheticType(aggStateTypes));

    initBlock.add(accPhysType.record(initExpressions));

    final Expression accumulatorInitializer =
        builder.append(
            "accumulatorInitializer",
            Expressions.lambda(
                Function0.class,
                initBlock.toBlock()));

    // Function2<Object[], Employee, Object[]> accumulatorAdder =
    //     new Function2<Object[], Employee, Object[]>() {
    //         public Object[] apply(Object[] acc, Employee in) {
    //              acc[0] = ((Integer) acc[0]) + 1;
    //              acc[1] = ((Integer) acc[1]) + in.salary;
    //             return acc;
    //         }
    //     };
    final BlockBuilder builder2 = new BlockBuilder();
    final ParameterExpression inParameter =
        Expressions.parameter(inputPhysType.getJavaRowType(), "in");
    final ParameterExpression acc_ =
        Expressions.parameter(accPhysType.getJavaRowType(), "acc");
    for (int i = 0, stateOffset = 0; i < aggs.size(); i++) {
      final AggImpState agg = aggs.get(i);

      int stateSize = agg.state.size();
      List<Expression> accumulator =
          new ArrayList<Expression>(stateSize);
      for (int j = 0; j < stateSize; j++) {
        accumulator.add(accPhysType.fieldReference(acc_, j + stateOffset));
      }
      agg.state = accumulator;

      stateOffset += stateSize;

      AggAddContext addContext =
          new AggAddContextImpl(builder2, accumulator) {
            public List<RexNode> rexArguments() {
              List<RelDataTypeField> inputTypes =
                  inputPhysType.getRowType().getFieldList();
              List<RexNode> args = new ArrayList<RexNode>();
              for (Integer index : agg.call.getArgList()) {
                args.add(
                    new RexInputRef(index, inputTypes.get(index).getType()));
              }
              return args;
            }

            public RexToLixTranslator rowTranslator() {
              return RexToLixTranslator.forAggregation(typeFactory,
                  currentBlock(),
                  new RexToLixTranslator.InputGetterImpl(
                      Collections.singletonList(
                          Pair.of((Expression) inParameter, inputPhysType))))
                  .setNullable(currentNullables());
            }
          };

      agg.implementor.implementAdd(agg.context, addContext);
    }
    builder2.add(acc_);
    final Expression accumulatorAdder =
        builder.append(
            "accumulatorAdder",
            Expressions.lambda(
                Function2.class,
                builder2.toBlock(),
                acc_,
                inParameter));

    // Function2<Integer, Object[], Object[]> resultSelector =
    //     new Function2<Integer, Object[], Object[]>() {
    //         public Object[] apply(Integer key, Object[] acc) {
    //             return new Object[] { key, acc[0], acc[1] };
    //         }
    //     };
    final BlockBuilder resultBlock = new BlockBuilder();
    final List<Expression> results = Expressions.list();
    final ParameterExpression key_;
    if (groupCount == 0) {
      key_ = null;
    } else {
      final Type keyType = keyPhysType.getJavaRowType();
      key_ = Expressions.parameter(keyType, "key");
      for (int j = 0; j < groupCount + indicatorCount; j++) {
        results.add(
            keyPhysType.fieldReference(key_, j));
      }
    }
    for (final AggImpState agg : aggs) {
      results.add(
          agg.implementor.implementResult(agg.context,
              new AggResultContextImpl(resultBlock, agg.state)));
    }
    resultBlock.add(physType.record(results));
    if (getGroupType() != Group.SIMPLE) {
      final List<Expression> list = Lists.newArrayList();
      for (ImmutableBitSet set : groupSets) {
        list.add(
            inputPhysType.generateSelector(parameter, groupSet.toList(),
                set.toList(), keyPhysType.getFormat()));
      }
      final Expression keySelectors_ =
          builder.append("keySelectors",
              Expressions.call(BuiltInMethod.ARRAYS_AS_LIST.method,
                  list));
      final Expression resultSelector =
          builder.append("resultSelector",
              Expressions.lambda(Function2.class,
                  resultBlock.toBlock(),
                  key_,
                  acc_));
      builder.add(
          Expressions.return_(null,
              Expressions.call(
                  BuiltInMethod.GROUP_BY_MULTIPLE.method,
                  Expressions.list(childExp,
                      keySelectors_,
                      accumulatorInitializer,
                      accumulatorAdder,
                      resultSelector)
                      .appendIfNotNull(keyPhysType.comparer()))));
    } else if (groupCount == 0) {
      final Expression resultSelector =
          builder.append(
              "resultSelector",
              Expressions.lambda(
                  Function1.class,
                  resultBlock.toBlock(),
                  acc_));
      builder.add(
          Expressions.return_(
              null,
              Expressions.call(
                  BuiltInMethod.SINGLETON_ENUMERABLE.method,
                  Expressions.call(
                      childExp,
                      BuiltInMethod.AGGREGATE.method,
                      Expressions.call(accumulatorInitializer, "apply"),
                      accumulatorAdder,
                      resultSelector))));
    } else if (aggCalls.isEmpty()
        && groupSet.equals(
            ImmutableBitSet.range(child.getRowType().getFieldCount()))) {
      builder.add(
          Expressions.return_(
              null,
              Expressions.call(
                  inputPhysType.convertTo(childExp, physType),
                  BuiltInMethod.DISTINCT.method,
                  Expressions.<Expression>list()
                      .appendIfNotNull(physType.comparer()))));
    } else {
      final Expression keySelector_ =
          builder.append("keySelector",
              inputPhysType.generateSelector(parameter,
                  groupSet.toList(),
                  keyPhysType.getFormat()));
      final Expression resultSelector_ =
          builder.append("resultSelector",
              Expressions.lambda(Function2.class,
                  resultBlock.toBlock(),
                  key_,
                  acc_));
      builder.add(
          Expressions.return_(null,
              Expressions.call(childExp,
                  BuiltInMethod.GROUP_BY2.method,
                  Expressions.list(keySelector_,
                      accumulatorInitializer,
                      accumulatorAdder,
                      resultSelector_)
                      .appendIfNotNull(keyPhysType.comparer()))));
    }
    return implementor.result(physType, builder.toBlock());
  }
}

// End EnumerableAggregate.java
