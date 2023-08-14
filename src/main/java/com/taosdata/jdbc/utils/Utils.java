package com.taosdata.jdbc.utils;

import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.deparser.StatementDeParser;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Utils {

    private static final Pattern ptn = Pattern.compile(".*?'");
    private static final DateTimeFormatter milliSecFormatter = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss.SSS").toFormatter();
    private static final DateTimeFormatter microSecFormatter = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").toFormatter();
    private static final DateTimeFormatter nanoSecFormatter = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS").toFormatter();
    private static final Pattern FROM_PATTERN_WITHOUT_BACKTICK = Pattern.compile("FROM\\s+(?:(\\w+)\\.)?(\\w+)", Pattern.CASE_INSENSITIVE);


    public static Time parseTime(String timestampStr) throws DateTimeParseException {
        LocalDateTime dateTime = parseLocalDateTime(timestampStr);
        return dateTime != null ? Time.valueOf(dateTime.toLocalTime()) : null;
    }

    public static Date parseDate(String timestampStr) {
        LocalDateTime dateTime = parseLocalDateTime(timestampStr);
        return dateTime != null ? Date.valueOf(String.valueOf(dateTime)) : null;
    }

    public static Timestamp parseTimestamp(String timeStampStr) {
        LocalDateTime dateTime = parseLocalDateTime(timeStampStr);
        return dateTime != null ? Timestamp.valueOf(dateTime) : null;
    }

    private static LocalDateTime parseLocalDateTime(String timeStampStr) {
        try {
            return parseMilliSecTimestamp(timeStampStr);
        } catch (DateTimeParseException e) {
            try {
                return parseMicroSecTimestamp(timeStampStr);
            } catch (DateTimeParseException ee) {
                return parseNanoSecTimestamp(timeStampStr);
            }
        }
    }

    private static LocalDateTime parseMilliSecTimestamp(String timeStampStr) throws DateTimeParseException {
        return LocalDateTime.parse(timeStampStr, milliSecFormatter);
    }

    private static LocalDateTime parseMicroSecTimestamp(String timeStampStr) throws DateTimeParseException {
        return LocalDateTime.parse(timeStampStr, microSecFormatter);
    }

    private static LocalDateTime parseNanoSecTimestamp(String timeStampStr) throws DateTimeParseException {
        return LocalDateTime.parse(timeStampStr, nanoSecFormatter);
    }

    public static String escapeSingleQuota(String origin) {
        Matcher m = ptn.matcher(origin);
        StringBuilder sb = new StringBuilder();
        int end = 0;
        while (m.find()) {
            end = m.end();
            String seg = origin.substring(m.start(), end);
            int len = seg.length();
            if (len == 1) {
                if ('\'' == seg.charAt(0)) {
                    sb.append("\\'");
                } else {
                    sb.append(seg);
                }
            } else { // len > 1
                sb.append(seg, 0, seg.length() - 2);
                char lastcSec = seg.charAt(seg.length() - 2);
                if (lastcSec == '\\') {
                    sb.append("\\'");
                } else {
                    sb.append(lastcSec);
                    sb.append("\\'");
                }
            }
        }

        if (end < origin.length()) {
            sb.append(origin.substring(end));
        }
        return sb.toString();
    }

    public static String getNativeSql(String rawSql, Object[] parameters) {
        if (parameters == null || !rawSql.contains("?"))
            return rawSql;
        // toLowerCase
        String preparedSql = rawSql.trim().toLowerCase();
        String[] clause = new String[]{"tags\\s*\\([\\s\\S]*?\\)", "where[\\s\\S]*"};
        Map<Integer, Integer> placeholderPositions = new HashMap<>();
        RangeSet<Integer> clauseRangeSet = TreeRangeSet.create();
        findPlaceholderPosition(preparedSql, placeholderPositions);
        // find tags and where clause's position
        findClauseRangeSet(preparedSql, clause, clauseRangeSet);
        // find values clause's position
        findValuesClauseRangeSet(preparedSql, clauseRangeSet);

        return transformSql(rawSql, parameters, placeholderPositions, clauseRangeSet);
    }

    private static void findValuesClauseRangeSet(String preparedSql, RangeSet<Integer> clauseRangeSet) {
        Matcher matcher = Pattern.compile("(values||,)\\s*(\\([^)]*\\))").matcher(preparedSql);
        while (matcher.find()) {
            int start = matcher.start(2);
            int end = matcher.end(2);
            clauseRangeSet.add(Range.closedOpen(start, end));
        }
    }

    private static void findClauseRangeSet(String preparedSql, String[] regexArr, RangeSet<Integer> clauseRangeSet) {
        clauseRangeSet.clear();
        for (String regex : regexArr) {
            Matcher matcher = Pattern.compile(regex).matcher(preparedSql);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                clauseRangeSet.add(Range.closedOpen(start, end));
            }
        }
    }

    private static void findPlaceholderPosition(String preparedSql, Map<Integer, Integer> placeholderPosition) {
        placeholderPosition.clear();
        Matcher matcher = Pattern.compile("\\?").matcher(preparedSql);
        int index = 0;
        while (matcher.find()) {
            int pos = matcher.start();
            placeholderPosition.put(index, pos);
            index++;
        }
    }

    /***
     *
     * @param rawSql
     * @param paramArr
     * @param placeholderPosition
     * @param clauseRangeSet
     * @return
     */
    private static String transformSql(String rawSql, Object[] paramArr, Map<Integer, Integer> placeholderPosition, RangeSet<Integer> clauseRangeSet) {
        String[] sqlArr = rawSql.split("\\?");

        return IntStream.range(0, sqlArr.length).mapToObj(index -> {
            if (index == paramArr.length)
                return sqlArr[index];

            Object para = paramArr[index];
            String paraStr;
            if (para != null) {
                if (para instanceof byte[]) {
                    paraStr = new String((byte[]) para, StandardCharsets.UTF_8);
                } else {
                    paraStr = para.toString();
                }
                // if para is timestamp or String or byte[] need to translate ' character
                if (para instanceof Timestamp || para instanceof String || para instanceof byte[]) {
                    paraStr = Utils.escapeSingleQuota(paraStr);

                    Integer pos = placeholderPosition.get(index);
                    boolean contains = clauseRangeSet.contains(pos);
                    if (contains) {
                        paraStr = "'" + paraStr + "'";
                    }
                }
            } else {
                paraStr = "NULL";
            }
            return sqlArr[index] + paraStr;
        }).collect(Collectors.joining());
    }

    public static String addBacktick(String rawSql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(rawSql);
            // 创建一个StatementDeParser对象，用于解析和重建SQL语句
            StatementDeParser deParser = new TableNameModifierVisitor(new StringBuilder());
            statement.accept(deParser);
            return deParser.getBuffer().toString();
        } catch (JSQLParserException ignored) {
        }
        return rawSql;
    }

    public static ClassLoader getClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null)
            return Utils.class.getClassLoader();
        else
            return cl;
    }

    public static Class<?> parseClassType(String key) {
        ClassLoader contextClassLoader = Utils.getClassLoader();
        try {
            return Class.forName(key, true, contextClassLoader);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Instantiate the class
     */
    public static <T> T newInstance(Class<T> c) {
        if (c == null)
            throw new RuntimeException("class cannot be null");
        try {
            Constructor<T> declaredConstructor = c.getDeclaredConstructor();
            declaredConstructor.setAccessible(true);
            return declaredConstructor.newInstance();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Could not find a public no-argument constructor for " + c.getName(), e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new RuntimeException("Could not instantiate class " + c.getName(), e);
        }
    }

    private static class TableNameModifierVisitor extends StatementDeParser {

        public TableNameModifierVisitor(StringBuilder buffer) {
            super(buffer);
        }

        private void modify(Table table) {
            String schemaName = table.getSchemaName();
            String tableName = table.getName();
            if (isUnquoted(schemaName)) {
                table.setSchemaName("`" + schemaName + "`");
            }
            if (isUnquoted(tableName)) {
                table.setName("`" + tableName + "`");
            }
        }

        private boolean isUnquoted(String s) {
            return !StringUtils.isEmpty(s) && !s.startsWith("`") && !s.endsWith("`");
        }

        @Override
        public void visit(DescribeStatement describe) {
            modify(describe.getTable());
            super.visit(describe);
        }

        @Override
        public void visit(CreateTable createTable) {
            modify(createTable.getTable());
            super.visit(createTable);
        }

        public void visit(CreateIndex createIndex) {
            modify(createIndex.getTable());
            super.visit(createIndex);
        }

        @Override
        public void visit(Delete delete) {
            modify(delete.getTable());
            super.visit(delete);
        }

        @Override
        public void visit(Select select) {
            PlainSelect plainSelect = select.getSelectBody(PlainSelect.class);
            FromItem fromItem = plainSelect.getFromItem();
            if (fromItem instanceof Table) {
                modify((Table) fromItem);
            }
            super.visit(select);
        }

        @Override
        public void visit(Insert insert) {
            modify(insert.getTable());
            super.visit(insert);
        }

        @Override
        public void visit(Update update) {
            modify(update.getTable());
            super.visit(update);
        }
    }
}
