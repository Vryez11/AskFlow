package io.askflow.pipeline.datasource.guard

import net.sf.jsqlparser.expression.BinaryExpression
import net.sf.jsqlparser.expression.BooleanValue
import net.sf.jsqlparser.expression.CaseExpression
import net.sf.jsqlparser.expression.CastExpression
import net.sf.jsqlparser.expression.CollateExpression
import net.sf.jsqlparser.expression.DateTimeLiteralExpression
import net.sf.jsqlparser.expression.DateValue
import net.sf.jsqlparser.expression.DoubleValue
import net.sf.jsqlparser.expression.ExtractExpression
import net.sf.jsqlparser.expression.HexValue
import net.sf.jsqlparser.expression.IntervalExpression
import net.sf.jsqlparser.expression.LongValue
import net.sf.jsqlparser.expression.NotExpression
import net.sf.jsqlparser.expression.NullValue
import net.sf.jsqlparser.expression.OrderByClause
import net.sf.jsqlparser.expression.PartitionByClause
import net.sf.jsqlparser.expression.SignedExpression
import net.sf.jsqlparser.expression.StringValue
import net.sf.jsqlparser.expression.TimeKeyExpression
import net.sf.jsqlparser.expression.TimeValue
import net.sf.jsqlparser.expression.TimestampValue
import net.sf.jsqlparser.expression.TrimFunction
import net.sf.jsqlparser.expression.WhenClause
import net.sf.jsqlparser.expression.WindowDefinition
import net.sf.jsqlparser.expression.WindowElement
import net.sf.jsqlparser.expression.WindowOffset
import net.sf.jsqlparser.expression.WindowRange
import net.sf.jsqlparser.expression.operators.relational.Between
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression
import net.sf.jsqlparser.expression.operators.relational.ExpressionList
import net.sf.jsqlparser.expression.operators.relational.InExpression
import net.sf.jsqlparser.expression.operators.relational.IsBooleanExpression
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.create.table.ColDataType
import net.sf.jsqlparser.statement.select.OrderByElement

/**
 * 개인정보 판정에서 안전하다고 아는 것의 목록 (design 7장 "판정 방향").
 * 여기 없는 함수와 해석 트리 노드는 개인정보로 본다. 늘릴 때는 스펙 검토를 거친다.
 */
internal object BuiltinAllowList {
    /**
     * 분석에 흔한 날짜, 문자열, 숫자, 조건, 형 변환 함수와 값을 숨기는 집계, 순위 창 함수. 대문자.
     * MIN, MAX, GROUP_CONCAT, FIRST_VALUE, LAG처럼 원래 값을 그대로 돌려줄 수 있는 집계·창 함수는 넣지 않는다.
     */
    private val FUNCTIONS =
        setOf(
            // 날짜
            "DATE",
            "TIME",
            "YEAR",
            "QUARTER",
            "MONTH",
            "MONTHNAME",
            "WEEK",
            "WEEKDAY",
            "WEEKOFYEAR",
            "YEARWEEK",
            "DAY",
            "DAYOFMONTH",
            "DAYOFWEEK",
            "DAYOFYEAR",
            "DAYNAME",
            "HOUR",
            "MINUTE",
            "SECOND",
            "LAST_DAY",
            "DATE_FORMAT",
            "TIME_FORMAT",
            "STR_TO_DATE",
            "DATE_ADD",
            "DATE_SUB",
            "ADDDATE",
            "SUBDATE",
            "DATEDIFF",
            "TIMESTAMPDIFF",
            "TIMESTAMPADD",
            "TO_DAYS",
            "UNIX_TIMESTAMP",
            "FROM_UNIXTIME",
            "MAKEDATE",
            "NOW",
            "CURDATE",
            "CURRENT_DATE",
            "CURRENT_TIMESTAMP",
            // 문자열
            "UPPER",
            "LOWER",
            "UCASE",
            "LCASE",
            "CONCAT",
            "CONCAT_WS",
            "LEFT",
            "RIGHT",
            "SUBSTRING",
            "SUBSTR",
            "MID",
            "LENGTH",
            "CHAR_LENGTH",
            "TRIM",
            "LTRIM",
            "RTRIM",
            "REPLACE",
            "LPAD",
            "RPAD",
            "REVERSE",
            "LOCATE",
            "INSTR",
            "FORMAT",
            // 숫자
            "ROUND",
            "FLOOR",
            "CEIL",
            "CEILING",
            "ABS",
            "TRUNCATE",
            "MOD",
            "POWER",
            "POW",
            "SQRT",
            "EXP",
            "LN",
            "LOG",
            "LOG10",
            "SIGN",
            "GREATEST",
            "LEAST",
            // 조건
            "IF",
            "IFNULL",
            "NULLIF",
            "COALESCE",
            // 값을 숨기는 집계
            "COUNT",
            "SUM",
            "AVG",
            "STD",
            "STDDEV",
            "STDDEV_POP",
            "STDDEV_SAMP",
            "VARIANCE",
            "VAR_POP",
            "VAR_SAMP",
            // 순위 창 함수
            "ROW_NUMBER",
            "RANK",
            "DENSE_RANK",
            "NTILE",
            "PERCENT_RANK",
            "CUME_DIST",
        )

    /** 첫 인자가 단위 키워드인 함수. JSqlParser는 단위(`DAY`)를 컬럼으로 읽는다 */
    private val UNIT_FIRST_FUNCTIONS = setOf("TIMESTAMPDIFF", "TIMESTAMPADD")
    private val UNITS = setOf("MICROSECOND", "SECOND", "MINUTE", "HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR")

    /** 값이 SQL에 적힌 그대로인 잎 */
    private val LITERALS: Set<Class<*>> =
        setOf(
            LongValue::class.java,
            DoubleValue::class.java,
            StringValue::class.java,
            NullValue::class.java,
            HexValue::class.java,
            BooleanValue::class.java,
            DateValue::class.java,
            TimeValue::class.java,
            TimestampValue::class.java,
            DateTimeLiteralExpression::class.java,
            TimeKeyExpression::class.java,
        )

    /** 재료를 섞기만 하는 노드. 안쪽을 마저 훑는다 */
    private val COMBINERS: Set<Class<*>> =
        setOf(
            SignedExpression::class.java,
            NotExpression::class.java,
            CaseExpression::class.java,
            WhenClause::class.java,
            CastExpression::class.java,
            ColDataType::class.java,
            ExtractExpression::class.java,
            IntervalExpression::class.java,
            TrimFunction::class.java,
            CollateExpression::class.java,
            ExpressionList::class.java,
            InExpression::class.java,
            Between::class.java,
            IsNullExpression::class.java,
            IsBooleanExpression::class.java,
            ExistsExpression::class.java,
            WindowDefinition::class.java,
            OrderByClause::class.java,
            PartitionByClause::class.java,
            OrderByElement::class.java,
            WindowElement::class.java,
            WindowOffset::class.java,
            WindowRange::class.java,
        )

    fun isSafeFunction(name: String): Boolean = name.uppercase() in FUNCTIONS

    fun isLiteral(node: Any): Boolean = node.javaClass in LITERALS

    // 사칙연산, 비교, AND·OR 같은 이항 연산은 두 재료를 섞기만 한다. ExpressionList는 괄호 목록 같은 하위 클래스가 있다
    fun isCombiner(node: Any): Boolean = node.javaClass in COMBINERS || node is BinaryExpression || node is ExpressionList<*>

    /** 함수의 [first] 인자가 단위 키워드라 컬럼으로 볼 필요가 없는지 */
    fun isUnitArgument(
        function: String,
        first: Any?,
    ): Boolean =
        function.uppercase() in UNIT_FIRST_FUNCTIONS &&
            first is Column &&
            first.table == null &&
            first.columnName.uppercase() in UNITS
}
