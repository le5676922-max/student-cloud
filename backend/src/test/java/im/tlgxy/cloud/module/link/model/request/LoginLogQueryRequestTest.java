package im.tlgxy.cloud.module.link.model.request;

import cn.hutool.core.date.DateUtil;
import im.tlgxy.cloud.core.exception.core.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoginLogQueryRequestTest {
    @Test
    void defaultsUseBoundedFirstPageAndLatestLoginOrder() {
        var query = new QueryLoginLogRequest();
        assertDoesNotThrow(query::validateForQuery);
        assertEquals(1, query.getPage());
        assertEquals(10, query.getLimit());
        assertEquals("create_time", query.getOrderItem().getColumn());
        assertFalse(query.getOrderItem().isAsc());
    }

    @ParameterizedTest
    @CsvSource({"id,id", "username,username", "create_time,create_time", "createTime,create_time", "ip,ip", "result,result"})
    void approvedSortAliasesResolveToFixedColumnNames(String field, String column) {
        var query = new QueryLoginLogRequest();
        query.setOrderBy(field);
        query.setOrderDirection("asc");
        assertEquals(column, query.getOrderItem().getColumn());
        assertTrue(query.getOrderItem().isAsc());
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "userAgent", "username desc", "create_time, id", "id; DROP TABLE login_log", "", "`id`"})
    void unexpectedSortColumnsNeverBecomeSql(String field) {
        var query = new QueryLoginLogRequest();
        query.setOrderBy(field);
        assertThrows(BizException.class, query::getOrderItem);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASC", "descending", "", "asc; SELECT 1"})
    void unexpectedSortDirectionsAreRejected(String direction) {
        var query = new QueryLoginLogRequest();
        query.setOrderDirection(direction);
        assertThrows(BizException.class, query::validateForQuery);
    }

    @ParameterizedTest
    @CsvSource({"0,10", "-1,10", "10001,10", "1,0", "1,-1", "1,101", "1,99999999"})
    void invalidPaginationIsRejected(int page, int limit) {
        var query = new QueryLoginLogRequest();
        query.setPage(page);
        query.setLimit(limit);
        assertThrows(BizException.class, query::validateForQuery);
    }

    @Test
    void maximumPaginationBoundsRemainValid() {
        var query = new QueryLoginLogRequest();
        query.setPage(10_000);
        query.setLimit(100);
        assertDoesNotThrow(query::validateForQuery);
    }

    @Test
    void nullPaginationAndOrderValuesAreRejected() {
        var query = new QueryLoginLogRequest();
        query.setPage(null);
        assertThrows(BizException.class, query::validateForQuery);
        query.setPage(1);
        query.setLimit(null);
        assertThrows(BizException.class, query::validateForQuery);
        query.setLimit(10);
        query.setOrderBy(null);
        assertThrows(BizException.class, query::validateForQuery);
        query.setOrderBy("id");
        query.setOrderDirection(null);
        assertThrows(BizException.class, query::validateForQuery);
    }

    @Test
    void emptyOrIncompleteDateRangesAreEquivalentToClearedPicker() {
        Date date = DateUtil.parse("2026-10-01 12:34:56");
        var query = new QueryLoginLogRequest();
        for (List<Date> range : Arrays.<List<Date>>asList(null, List.<Date>of(), List.of(date),
                Arrays.asList(date, null), Arrays.asList(null, date))) {
            query.setSearchDate(range);
            assertDoesNotThrow(query::validateForQuery);
            assertNull(query.getDateFrom());
            assertNull(query.getDateTo());
        }
    }

    @Test
    void completeDateRangeIncludesBothWholeDays() {
        Date start = DateUtil.parse("2026-10-01 12:34:56");
        Date end = DateUtil.parse("2026-10-02 04:05:06");
        var query = new QueryLoginLogRequest();
        query.setSearchDate(List.of(start, end));
        assertDoesNotThrow(query::validateForQuery);
        assertEquals(DateUtil.beginOfDay(start), query.getDateFrom());
        assertEquals(DateUtil.endOfDay(end), query.getDateTo());
    }

    @Test
    void reversedDatesAreRejectedEvenWithinTheSameDay() {
        var query = new QueryLoginLogRequest();
        query.setSearchDate(List.of(DateUtil.parse("2026-10-02 14:00:00"), DateUtil.parse("2026-10-02 08:00:00")));
        assertThrows(BizException.class, query::validateForQuery);
        assertThrows(BizException.class, query::getDateFrom);
    }

    @Test
    void moreThanTwoDateValuesAreRejected() {
        var query = new QueryLoginLogRequest();
        Date date = new Date();
        query.setSearchDate(List.of(date, date, date));
        assertThrows(BizException.class, query::validateForQuery);
    }
}
