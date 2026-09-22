package com.github.h3yow.apigatewaygovernancejava.tests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单测统一日志：打印请求输入、判定依据与最终结果，保证结论可解释、可追溯。
 */
public final class TestLogger {

    private static final Logger LOG = LoggerFactory.getLogger("GOVERNANCE-TEST");

    private TestLogger() {
    }

    public static void log(String phase, String input, String basis, String result) {
        LOG.info("[{}] input={} | basis={} | result={}", phase, input, basis, result);
    }
}
