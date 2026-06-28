package com.pulse.expression.service;

import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.ListSqlOperatorTable;
import org.apache.calcite.sql.util.SqlOperatorTables;

import java.util.ArrayList;
import java.util.List;

/**
 * The EXTENSIBLE Spark-SQL function registry for {@link CalciteSqlModelValidator}
 * (SPEC #6 §A.2, P1). It is the union of:
 *
 * <ul>
 *   <li><b>Calcite's {@link SqlStdOperatorTable}</b> — the ANSI/SQL built-ins
 *       (arithmetic / comparison / logical operators, {@code CASE} / {@code COALESCE}
 *       / {@code NULLIF} / {@code CAST}, the standard aggregates {@code COUNT} /
 *       {@code SUM} / {@code AVG} / {@code MIN} / {@code MAX}, the window machinery
 *       {@code OVER} / {@code ROW_NUMBER} / {@code RANK} / {@code DENSE_RANK} /
 *       {@code LAG} / {@code LEAD}, and the standard scalar string/date functions
 *       {@code SUBSTRING} / {@code TRIM} / {@code UPPER} / {@code LOWER} /
 *       {@code CURRENT_DATE} / {@code CURRENT_TIMESTAMP}). These need no extra
 *       registration.</li>
 *   <li><b>Spark-SQL functions PULSE codegen emits</b> that Calcite's std table
 *       does not already cover, registered on top here.</li>
 * </ul>
 *
 * <p>The registry is <b>extensible</b> (SPEC #6 §A.2 "Source of truth +
 * extensibility"): a Spark function added to codegen later is registered here in
 * the same pass — add a {@link #fn} entry to {@link #SPARK_FUNCTIONS}. Per ADR
 * 0011, an unknown / unregistered function is a <b>loud build-fail</b>
 * ({@link BuildFailure.Cause#UNREGISTERED_FUNCTION}), never a silent pass.
 *
 * <p>This registry serves {@code sql-model} only (the Spark/dbt-SQL transform).
 * {@code SourceSQL} needs no PULSE function registry — its {@code source_query}
 * is validated by the source DB (SPEC #6 §C.2).
 */
final class SparkSqlFunctionRegistry {

    private SparkSqlFunctionRegistry() {
    }

    /**
     * The composed operator table: Calcite std built-ins chained with the Spark
     * functions registered on top. Used as the validator's operator table.
     */
    static SqlOperatorTable operatorTable() {
        ListSqlOperatorTable sparkTable = new ListSqlOperatorTable(SPARK_FUNCTIONS);
        // std first so well-known operators resolve via Calcite's canonical table;
        // the Spark table supplies the extra spellings/overloads on top.
        return SqlOperatorTables.chain(SqlStdOperatorTable.instance(), sparkTable);
    }

    /**
     * The Spark-SQL functions PULSE dbt/PySpark codegen emits that Calcite's
     * std table does not already cover (SPEC #6 §A.2 RESOLVED list, extended with
     * the codegen-example usages noted in IMPL-calcite-sql.md P1). Names match
     * the BABEL-parsed, case-insensitive spellings authors type.
     */
    private static final List<org.apache.calcite.sql.SqlOperator> SPARK_FUNCTIONS = buildSparkFunctions();

    private static List<org.apache.calcite.sql.SqlOperator> buildSparkFunctions() {
        List<org.apache.calcite.sql.SqlOperator> fns = new ArrayList<>();

        // --- hashing / encoding ---
        // SHA2(expr, bits) -> hex string; MD5(expr) -> hex string.
        fns.add(fn("SHA2", ReturnTypes.VARCHAR, OperandTypes.ANY_ANY));
        fns.add(fn("MD5", ReturnTypes.VARCHAR, OperandTypes.ANY));

        // --- string concat / manipulation ---
        // CONCAT_WS(sep, a, b, ...) -> string; CONCAT(a, b, ...) -> string.
        fns.add(fn("CONCAT_WS", ReturnTypes.VARCHAR, OperandTypes.VARIADIC));
        fns.add(fn("CONCAT", ReturnTypes.VARCHAR, OperandTypes.VARIADIC));
        fns.add(fn("REGEXP_REPLACE", ReturnTypes.VARCHAR, OperandTypes.VARIADIC));
        fns.add(fn("LPAD", ReturnTypes.VARCHAR, OperandTypes.VARIADIC));
        fns.add(fn("RPAD", ReturnTypes.VARCHAR, OperandTypes.VARIADIC));
        fns.add(fn("LENGTH", ReturnTypes.INTEGER_NULLABLE, OperandTypes.ANY));
        fns.add(fn("REPEAT", ReturnTypes.VARCHAR, OperandTypes.ANY_ANY));
        // SPLIT(str, pattern) -> array<string>.
        fns.add(fn("SPLIT", ReturnTypes.TO_ARRAY, OperandTypes.VARIADIC));

        // --- null handling (Spark spelling not in std table) ---
        // NVL(a, b) -> least-restrictive of a/b; Spark's COALESCE alias.
        fns.add(fn("NVL", ReturnTypes.LEAST_RESTRICTIVE, OperandTypes.SAME_SAME));

        // --- casting (Spark spelling) ---
        // TRY_CAST(expr AS type) parses as a 2-arg call in Babel; return type is
        // the cast target, so defer to the second operand's type, nullable.
        fns.add(fn("TRY_CAST", ReturnTypes.ARG1_NULLABLE, OperandTypes.ANY_ANY));

        // --- date / time ---
        // DATEDIFF(end, start) -> integer days; DATE_ADD / DATE_SUB(date, n) -> date.
        fns.add(fn("DATEDIFF", ReturnTypes.INTEGER_NULLABLE, OperandTypes.ANY_ANY));
        fns.add(fn("DATE_ADD", explicit(SqlTypeName.DATE), OperandTypes.VARIADIC));
        fns.add(fn("DATE_SUB", explicit(SqlTypeName.DATE), OperandTypes.VARIADIC));
        // DATE_FORMAT(date, fmt) -> string.
        fns.add(fn("DATE_FORMAT", ReturnTypes.VARCHAR, OperandTypes.ANY_ANY));
        // TO_DATE(expr[, fmt]) -> date; TO_TIMESTAMP(expr[, fmt]) -> timestamp.
        fns.add(fn("TO_DATE", explicit(SqlTypeName.DATE), OperandTypes.VARIADIC));
        fns.add(fn("TO_TIMESTAMP", explicit(SqlTypeName.TIMESTAMP), OperandTypes.VARIADIC));

        return fns;
    }

    /** A nullable, explicit-typed return-type inference for a fixed {@link SqlTypeName}. */
    private static org.apache.calcite.sql.type.SqlReturnTypeInference explicit(SqlTypeName type) {
        return ReturnTypes.explicit(type).andThen(org.apache.calcite.sql.type.SqlTypeTransforms.TO_NULLABLE);
    }

    /** Builds a scalar {@link SqlFunction} with the given return/operand inference. */
    private static SqlFunction fn(String name,
                                  org.apache.calcite.sql.type.SqlReturnTypeInference returnType,
                                  org.apache.calcite.sql.type.SqlOperandTypeChecker operandChecker) {
        return new SqlFunction(
                name,
                SqlKind.OTHER_FUNCTION,
                returnType,
                null,            // no operand-type inference; checker handles validation
                operandChecker,
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }
}
