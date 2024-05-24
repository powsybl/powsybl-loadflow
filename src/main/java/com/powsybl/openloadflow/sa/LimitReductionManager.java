/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.contingency.ContingencyContextType;
import com.powsybl.iidm.criteria.AtLeastOneNominalVoltageCriterion;
import com.powsybl.iidm.criteria.IdentifiableCriterion;
import com.powsybl.iidm.criteria.VoltageInterval;
import com.powsybl.iidm.criteria.duration.AllTemporaryDurationCriterion;
import com.powsybl.iidm.criteria.duration.EqualityTemporaryDurationCriterion;
import com.powsybl.iidm.criteria.duration.IntervalTemporaryDurationCriterion;
import com.powsybl.iidm.criteria.duration.LimitDurationCriterion;
import com.powsybl.iidm.network.LimitType;
import com.powsybl.security.limitreduction.LimitReduction;
import org.apache.commons.lang3.DoubleRange;
import org.apache.commons.lang3.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 *
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class LimitReductionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(LimitReductionManager.class);

    /**
     * @param acceptableDuration can be null
     */
    public record TerminalLimitReduction(Range<Double> nominalV, boolean isPermanent, Range<Integer> acceptableDuration,
                                         double reduction) {

    }

    private final List<TerminalLimitReduction> terminalLimitReductions = new ArrayList<>();

    public boolean isEmpty() {
        return terminalLimitReductions.isEmpty();
    }

    public List<TerminalLimitReduction> getTerminalLimitReductions() {
        return terminalLimitReductions;
    }

    public void addTerminalLimitReduction(TerminalLimitReduction terminalLimitReduction) {
        this.terminalLimitReductions.add(terminalLimitReduction);
    }

    public static LimitReductionManager create(List<LimitReduction> limitReductions) {
        LimitReductionManager limitReductionManager = new LimitReductionManager();
        Range<Integer> acceptableDurationRange;
        boolean permanent;
        for (LimitReduction limitReduction : limitReductions) {
            if (isSupported(limitReduction)) {
                // Compute the duration data
                permanent = false;
                acceptableDurationRange = null;
                if (limitReduction.getDurationCriteria().isEmpty()) {
                    // When no duration criterion is present, the reduction applies to permanent and temporary limits
                    permanent = true;
                    acceptableDurationRange = Range.of(0, Integer.MAX_VALUE);
                } else { // size 1 or 2 only (when 2, they are not of the same type).
                    for (LimitDurationCriterion limitDurationCriterion : limitReduction.getDurationCriteria()) {
                        LimitDurationCriterion.LimitDurationType type = limitDurationCriterion.getType();
                        if (Objects.requireNonNull(type) == LimitDurationCriterion.LimitDurationType.PERMANENT) {
                            permanent = true;
                        } else if (type == LimitDurationCriterion.LimitDurationType.TEMPORARY) {
                            acceptableDurationRange = getAcceptableDurationRange(limitDurationCriterion);
                        }
                    }
                }
                // Compute the nominal voltage ranges. When no network element criteria is present,
                // the reduction applies to all network elements.
                Collection<DoubleRange> nominalVoltageRanges = limitReduction.getNetworkElementCriteria().isEmpty() ?
                        List.of(DoubleRange.of(0, Double.MAX_VALUE)) :
                        limitReduction.getNetworkElementCriteria().stream().map(IdentifiableCriterion.class::cast)
                                .map(IdentifiableCriterion::getNominalVoltageCriterion)
                                .map(AtLeastOneNominalVoltageCriterion::getVoltageInterval)
                                .map(VoltageInterval::asRange)
                                .distinct()
                                .toList();

                for (DoubleRange nominalVoltageRange : nominalVoltageRanges) {
                    limitReductionManager.addTerminalLimitReduction(new TerminalLimitReduction(nominalVoltageRange, permanent, acceptableDurationRange, limitReduction.getValue()));
                }
            }
        }
        return limitReductionManager;
    }

    private static Range<Integer> getAcceptableDurationRange(LimitDurationCriterion limitDurationCriterion) {
        Range<Integer> acceptableDurationRange;
        if (limitDurationCriterion instanceof AllTemporaryDurationCriterion) {
            acceptableDurationRange = Range.of(0, Integer.MAX_VALUE);
        } else if (limitDurationCriterion instanceof EqualityTemporaryDurationCriterion equalityTemporaryDurationCriterion) {
            acceptableDurationRange = Range.of(equalityTemporaryDurationCriterion.getDurationEqualityValue(),
                    equalityTemporaryDurationCriterion.getDurationEqualityValue());
        } else { // intervalTemporaryDurationCriterion
            IntervalTemporaryDurationCriterion intervalTemporaryDurationCriterion = (IntervalTemporaryDurationCriterion) limitDurationCriterion;
            acceptableDurationRange = intervalTemporaryDurationCriterion.asRange();
        }
        return acceptableDurationRange;
    }

    private static boolean isSupported(LimitReduction limitReduction) {
        if (limitReduction.getContingencyContext().getContextType() != ContingencyContextType.ALL) {
            // Contingency context NONE with empty contingency lists could be supported too.
            LOGGER.warn("Only contingency context ALL is yet supported.");
            return false;
        }
        if (limitReduction.isMonitoringOnly()) {
            // This means that post-contingency limit violations with reductions must not be used for the conditions of
            // operator strategy.
            LOGGER.warn("Limit reductions for monitoring only is not yet supported.");
            return false;
        }
        if (limitReduction.getLimitType() != LimitType.CURRENT) {
            // Note: a list of limit types could be a good feature?
            LOGGER.warn("Only limit reductions for current limits are yet supported.");
            return false;
        }
        if (limitReduction.getNetworkElementCriteria().stream().anyMatch(Predicate.not(IdentifiableCriterion.class::isInstance))) {
            LOGGER.warn("Only no network element criterion or identifiable criteria are yet supported.");
            return false;
        }
        if (limitReduction.getDurationCriteria().size() > 2) {
            LOGGER.warn("More than two duration criteria provided.");
            return false;
        }
        if (limitReduction.getDurationCriteria().size() == 2
                && limitReduction.getDurationCriteria().get(0).getType() == limitReduction.getDurationCriteria().get(1).getType()) {
            LOGGER.warn("When two duration criteria are provided, they cannot be of the same type");
            return false;
        }
        return true;
    }
}
