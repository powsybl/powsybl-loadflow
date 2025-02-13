/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.util;

import com.google.common.io.ByteStreams;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.loadflow.LoadFlowResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static com.powsybl.commons.test.TestUtil.normalizeLineSeparator;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class LoadFlowAssert {

    public static final double DELTA_ANGLE = 1E-6d;
    public static final double DELTA_V = 1E-2d;
    public static final double DELTA_POWER = 1E-3d;
    public static final double DELTA_RHO = 1E-6d;
    public static final double DELTA_I = 1000 * DELTA_POWER / Math.sqrt(3);
    public static final double DELTA_MISMATCH = 1E-4d;
    public static final double DELTA_SENSITIVITY_VALUE = 1E-4d;

    private LoadFlowAssert() {
    }

    public static void assertVoltageEquals(double v, Bus bus) {
        assertEquals(v, bus.getV(), DELTA_V);
    }

    public static void assertAngleEquals(double a, Bus bus) {
        assertEquals(a, bus.getAngle(), DELTA_ANGLE);
    }

    public static void assertActivePowerEquals(double p, Terminal terminal) {
        assertEquals(p, terminal.getP(), DELTA_POWER);
    }

    public static void assertReactivePowerEquals(double q, Terminal terminal) {
        assertEquals(q, terminal.getQ(), DELTA_POWER);
    }

    public static void assertCurrentEquals(double i, Terminal terminal) {
        assertEquals(i, terminal.getI(), DELTA_I);
    }

    public static void assertUndefinedActivePower(Terminal terminal) {
        assertTrue(Double.isNaN(terminal.getP()));
    }

    public static void assertUndefinedReactivePower(Terminal terminal) {
        assertTrue(Double.isNaN(terminal.getQ()));
    }

    public static void assertLoadFlowResultsEquals(LoadFlowResult loadFlowResultExpected, LoadFlowResult loadFlowResult) {
        assertEquals(loadFlowResultExpected.getStatus(), loadFlowResult.getStatus(),
                "Wrong load flow status");
        assertEquals(loadFlowResultExpected.getComponentResults().size(),
                loadFlowResult.getComponentResults().size(),
                "Wrong sub network count");
        Iterator<LoadFlowResult.ComponentResult> componentResultIteratorExpected = loadFlowResultExpected.getComponentResults().iterator();
        Iterator<LoadFlowResult.ComponentResult> componentResultIterator = loadFlowResult.getComponentResults().iterator();
        // loop over components
        while (componentResultIteratorExpected.hasNext()) {
            LoadFlowResult.ComponentResult componentResultExpected = componentResultIteratorExpected.next();
            LoadFlowResult.ComponentResult componentResult = componentResultIterator.next();
            assertEquals(componentResultExpected.getStatus(),
                    componentResult.getStatus(),
                    "Wrong load flow result status");
            assertEquals(componentResultExpected.getIterationCount(),
                    componentResult.getIterationCount(),
                    "Wrong iteration count");
            assertEquals(componentResultExpected.getSlackBusResults().size(),
                    componentResult.getSlackBusResults().size(),
                    "Wrong slack bus results size");
            if (!componentResult.getSlackBusResults().isEmpty()) {
                assertEquals(componentResultExpected.getSlackBusResults().get(0).getActivePowerMismatch(),
                        componentResult.getSlackBusResults().get(0).getActivePowerMismatch(), DELTA_MISMATCH,
                        "Wrong active power mismatch");
            }
        }
    }

    public static void assertReportEquals(String refResourceName, ReportNode reportNode) throws IOException {
        assertReportEquals(LoadFlowAssert.class.getResourceAsStream(refResourceName), reportNode);
    }

    private static String reportToString(ReportNode reportNode) throws IOException {
        StringWriter sw = new StringWriter();
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator('.');
        DecimalFormat decimalFormat = new DecimalFormat("#.######", symbols);
        reportNode.print(sw, typedValue -> {
            if (typedValue.getValue() instanceof Double) {
                return decimalFormat.format(typedValue.getValue());
            }
            return typedValue.toString();
        });
        return sw.toString();
    }

    public static void assertReportEquals(InputStream ref, ReportNode reportNode) throws IOException {
        String refLogExport = normalizeLineSeparator(new String(ByteStreams.toByteArray(ref), StandardCharsets.UTF_8));
        String logExport = normalizeLineSeparator(reportToString(reportNode));
        assertEquals(refLogExport, logExport);
    }

    public static void assertTxtReportEquals(String reportTxt, ReportNode reportNode) throws IOException {
        String refLogExport = normalizeLineSeparator(reportTxt);
        String logExport = normalizeLineSeparator(reportToString(reportNode));
        assertEquals(refLogExport, logExport);
    }

    public static Stream<ReportNode> streamReportNodes(final ReportNode node) {
        return Stream.concat(Stream.of(node), node.getChildren().stream().flatMap(LoadFlowAssert::streamReportNodes));
    }

    public static void assertReportContains(String regex, ReportNode reportNode) {
        List<ReportNode> matching = streamReportNodes(reportNode).filter(node -> node.getMessage().matches(regex)).toList();
        assertFalse(matching.isEmpty(), () -> {
            StringWriter sw = new StringWriter();
            try {
                reportNode.print(sw);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String txtReport = normalizeLineSeparator(sw.toString());
            return "Report does not contain '" + regex + "': \n-----\n" + txtReport;
        });
    }
}
