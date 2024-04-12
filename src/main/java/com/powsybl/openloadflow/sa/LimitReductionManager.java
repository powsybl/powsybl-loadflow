/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.contingency.ContingencyContextType;
import com.powsybl.iidm.criteria.IdentifiableCriterion;
import com.powsybl.iidm.criteria.NetworkElementCriterion;
import com.powsybl.iidm.criteria.duration.*;
import com.powsybl.iidm.network.LimitType;
import com.powsybl.security.limitreduction.LimitReduction;
import org.apache.commons.lang3.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 *
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class LimitReductionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(LimitReductionManager.class);

    public static class TerminalLimitReduction {

        Range<Double> nominalV;
        Range<Integer> acceptableDuration; // can be null
        boolean isPermanent;
        double reduction;

        public TerminalLimitReduction(Range<Double> nominalV, boolean isPermanent, Range<Integer> acceptableDuration, double reduction) {
            this.nominalV = nominalV;
            this.isPermanent = isPermanent;
            this.acceptableDuration = acceptableDuration;
            this.reduction = reduction;
        }

        public boolean isPermanent() {
            return isPermanent;
        }

        public double getReduction() {
            return reduction;
        }

        public Range<Double> getNominalV() {
            return nominalV;
        }

        public Range<Integer> getAcceptableDuration() {
            return acceptableDuration;
        }
    }

    List<TerminalLimitReduction> terminalLimitReductions = new ArrayList<>();

    public LimitReductionManager() {
    }

    public boolean isEmpty() {
        return terminalLimitReductions.isEmpty();
    }

    public List<TerminalLimitReduction> getTerminalLimitReductions() {
        // to be sorted by acceptable duration
        // return terminalLimitReductions.stream().sorted().toList();
        return terminalLimitReductions;
    }

    public void addTerminalLimitReduction(TerminalLimitReduction terminalLimitReduction) {
        this.terminalLimitReductions.add(terminalLimitReduction);
    }

    public static LimitReductionManager create(List<LimitReduction> limitReductions) {
        LimitReductionManager limitReductionManager = new LimitReductionManager();
        Range<Double> nominalVoltageRange;
        Range<Integer> acceptableDurationRange = null; // nothing asked
        boolean permanent = false;
        for (LimitReduction limitReduction : limitReductions) {
            if (isSupported(limitReduction)) {
                if (limitReduction.getNetworkElementCriteria().isEmpty()) {
                    // nothing todo
                } else {
                    for (NetworkElementCriterion networkElementCriterion : limitReduction.getNetworkElementCriteria()) {
                        IdentifiableCriterion identifiableCriterion = (IdentifiableCriterion) networkElementCriterion;
                        nominalVoltageRange = identifiableCriterion.getNominalVoltageCriterion().getVoltageInterval().asRange();
                        if (limitReduction.getDurationCriteria().isEmpty()) {
                            permanent = true;
                        } else { // size 1 or 2 only.
                            for (LimitDurationCriterion limitDurationCriterion : limitReduction.getDurationCriteria()) {
                                switch (limitDurationCriterion.getType()) {
                                    case PERMANENT -> permanent = true;
                                    case TEMPORARY -> {
                                        if (limitDurationCriterion instanceof AllTemporaryDurationCriterion) {
                                            acceptableDurationRange = Range.of(0, Integer.MAX_VALUE);
                                        } else if (limitDurationCriterion instanceof EqualityTemporaryDurationCriterion) {
                                            EqualityTemporaryDurationCriterion equalityTemporaryDurationCriterion = (EqualityTemporaryDurationCriterion) limitDurationCriterion;
                                            acceptableDurationRange = Range.of(equalityTemporaryDurationCriterion.getDurationEqualityValue(),
                                                    equalityTemporaryDurationCriterion.getDurationEqualityValue());
                                        } else { // intervalTemporaryDurationCriterion
                                            IntervalTemporaryDurationCriterion intervalTemporaryDurationCriterion = (IntervalTemporaryDurationCriterion) limitDurationCriterion;
                                            acceptableDurationRange = intervalTemporaryDurationCriterion.asRange();
                                        }
                                    }
                                }
                            }
                        }
                        limitReductionManager.addTerminalLimitReduction(new TerminalLimitReduction(nominalVoltageRange, permanent, acceptableDurationRange, limitReduction.getValue()));
                    }
                }
            }
        }
        return limitReductionManager;
    }

    private static boolean isSupported(LimitReduction limitReduction) {
        if (!limitReduction.getContingencyContext().getContextType().equals(ContingencyContextType.ALL)) {
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
            LOGGER.warn("Only no network element criterion or identifiable criteria is yet supported.");
            return false;
        }
        if (limitReduction.getDurationCriteria().size() > 2) {
            LOGGER.warn("More than two duration criteria provided.");
            return false;
        }
        return true;
    }
}
