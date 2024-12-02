/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import org.apache.commons.lang3.Range;
import org.apache.commons.math3.complex.Complex;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class PiModelArray implements PiModel {

    private final List<PiModel> models;

    private final int lowTapPosition;

    private int tapPositionIndex;

    private double a1 = Double.NaN; // override a1 at current tap position if not NaN

    private double r1 = Double.NaN; // override r1 at current tap position if not NaN

    private double continuousR1 = Double.NaN;

    private LfBranch branch;

    private final double minR1;

    private final double maxR1;

    public PiModelArray(List<PiModel> models, int lowTapPosition, int tapPosition) {
        this.models = Objects.requireNonNull(models);
        this.lowTapPosition = lowTapPosition;
        tapPositionIndex = tapPosition - lowTapPosition;
        minR1 = this.models.stream().mapToDouble(PiModel::getMinR1).min().orElseThrow();
        maxR1 = this.models.stream().mapToDouble(PiModel::getMaxR1).max().orElseThrow();
    }

    List<PiModel> getModels() {
        return models;
    }

    private PiModel getModel() {
        return models.get(tapPositionIndex);
    }

    @Override
    public double getR() {
        return getModel().getR();
    }

    @Override
    public PiModel setR(double r) {
        return getModel().setR(r);
    }

    @Override
    public double getX() {
        return getModel().getX();
    }

    @Override
    public PiModel setX(double x) {
        return getModel().setX(x);
    }

    @Override
    public double getZ() {
        return getModel().getZ();
    }

    @Override
    public double getY() {
        return getModel().getY();
    }

    @Override
    public double getKsi() {
        return getModel().getKsi();
    }

    @Override
    public double getG1() {
        return getModel().getG1();
    }

    @Override
    public double getB1() {
        return getModel().getB1();
    }

    @Override
    public double getG2() {
        return getModel().getG2();
    }

    @Override
    public double getB2() {
        return getModel().getB2();
    }

    public double getModifiedR1() {
        return r1;
    }

    public double getModifiedA1() {
        return a1;
    }

    @Override
    public double getR1() {
        return Double.isNaN(r1) ? getModel().getR1() : r1;
    }

    @Override
    public double getContinuousR1() {
        return continuousR1;
    }

    @Override
    public double getA1() {
        return Double.isNaN(a1) ? getModel().getA1() : a1;
    }

    @Override
    public PiModelArray setA1(double a1) {
        this.a1 = a1;
        return this;
    }

    @Override
    public PiModelArray setR1(double r1) {
        this.r1 = r1;
        return this;
    }

    interface TapPositionFinder {

        int find(List<PiModel> models, int tapPositionIndex, ToDoubleFunction<PiModel> valueGetter,
                 Range<Integer> positionIndexRange, int maxTapShift);
    }

    static class ClosestTapPositionFinder implements TapPositionFinder {

        private final double targetValue;

        ClosestTapPositionFinder(double targetValue) {
            this.targetValue = targetValue;
        }

        @Override
        public int find(List<PiModel> models, int tapPositionIndex, ToDoubleFunction<PiModel> valueGetter,
                        Range<Integer> positionIndexRange, int maxTapShift) {
            int closestTapPositionIndex = tapPositionIndex;
            double smallestDistance = Math.abs(targetValue - valueGetter.applyAsDouble(models.get(tapPositionIndex)));
            for (int i = positionIndexRange.getMinimum(); i <= positionIndexRange.getMaximum(); i++) {
                if (Math.abs(i - tapPositionIndex) > maxTapShift) { // we are not allowed to go further than maxTapShift positions
                    continue;
                }
                double distance = Math.abs(targetValue - valueGetter.applyAsDouble(models.get(i)));
                if (distance < smallestDistance) {
                    closestTapPositionIndex = i;
                    smallestDistance = distance;
                }
            }
            return closestTapPositionIndex;
        }
    }

    static class FirstTapPositionAboveFinder implements TapPositionFinder {

        private final double valueShift;

        FirstTapPositionAboveFinder(double valueShift) {
            this.valueShift = valueShift;
        }

        private static boolean isDescending(int n1, int n2, double valueShift, List<PiModel> models, ToDoubleFunction<PiModel> valueGetter) {
            return valueShift > 0 ? valueGetter.applyAsDouble(models.get(n1)) < valueGetter.applyAsDouble(models.get(n2))
                    : valueGetter.applyAsDouble(models.get(n1)) > valueGetter.applyAsDouble(models.get(n2));
        }

        static int nextTapPositionIndex(int i, double valueShift, List<PiModel> models, ToDoubleFunction<PiModel> valueGetter) {
            if (valueShift == 0) {
                return -1;
            }
            if (i == 0 && isDescending(i + 1, i, valueShift, models, valueGetter)) {
                return -1;
            }
            if (i > 0 && isDescending(i, i - 1, valueShift, models, valueGetter)) {
                return i - 1;
            }
            if (i < models.size() - 1 && isDescending(i, i + 1, valueShift, models, valueGetter)) {
                return i + 1;
            }
            if (i == models.size() - 1 && isDescending(i, i - 1, valueShift, models, valueGetter)) {
                return -1;
            }
            return -1;
        }

        @Override
        public int find(List<PiModel> models, int tapPositionIndex, ToDoubleFunction<PiModel> valueGetter,
                        Range<Integer> positionIndexRange, int maxTapShift) {
            int currentTapPositionIndex = tapPositionIndex;
            double remainingValueShift = valueShift;
            int nextTapPositionIndex;
            while ((nextTapPositionIndex = nextTapPositionIndex(currentTapPositionIndex, remainingValueShift, models, valueGetter)) != -1) {
                if (!positionIndexRange.contains(nextTapPositionIndex)
                        || Math.abs(nextTapPositionIndex - tapPositionIndex) > maxTapShift) {
                    break;
                }
                double value = valueGetter.applyAsDouble(models.get(currentTapPositionIndex));
                double nextValue = valueGetter.applyAsDouble(models.get(nextTapPositionIndex));
                currentTapPositionIndex = nextTapPositionIndex;
                // stop when shift is not enough to go to next position
                if (remainingValueShift < 0 && value + remainingValueShift > nextValue
                        || remainingValueShift > 0 && value + remainingValueShift < nextValue) {
                    break;
                }
                remainingValueShift -= nextValue - value;
            }
            return currentTapPositionIndex;
        }
    }

    private Optional<Direction> updateTapPosition(ToDoubleFunction<PiModel> valueGetter, Range<Integer> positionIndexRange,
                                                  int maxTapShift, TapPositionFinder finder) {
        int oldPositionIndex = tapPositionIndex;

        // find tap position with the closest value without exceeding the maximum of taps to switch.
        tapPositionIndex = finder.find(models, tapPositionIndex, valueGetter, positionIndexRange, maxTapShift);

        if (tapPositionIndex != oldPositionIndex) {
            for (LfNetworkListener listener : branch.getNetwork().getListeners()) {
                listener.onTapPositionChange(branch, lowTapPosition + oldPositionIndex, lowTapPosition + tapPositionIndex);
            }

            return Optional.of(tapPositionIndex - oldPositionIndex > 0 ? Direction.INCREASE : Direction.DECREASE);
        }

        return Optional.empty();
    }

    private Range<Integer> getAllowedPositionIndexRange(AllowedDirection allowedDirection) {
        switch (allowedDirection) {
            case INCREASE:
                return Range.of(tapPositionIndex, models.size() - 1);
            case DECREASE:
                return Range.of(0, tapPositionIndex);
            case BOTH:
                return Range.of(0, models.size() - 1);
            default:
                throw new IllegalStateException("Unknown direction: " + allowedDirection);
        }
    }

    @Override
    public void roundA1ToClosestTap() {
        if (Double.isNaN(a1)) {
            return; // nothing to do because a1 has not been modified
        }

        // find tap position with the closest a1 value
        updateTapPosition(PiModel::getA1, getAllowedPositionIndexRange(AllowedDirection.BOTH), Integer.MAX_VALUE,
                new ClosestTapPositionFinder(a1));
        a1 = Double.NaN;
    }

    @Override
    public void roundR1ToClosestTap() {
        if (Double.isNaN(r1)) {
            return; // nothing to do because r1 has not been modified
        }

        // find tap position with the closest r1 value
        updateTapPosition(PiModel::getR1, getAllowedPositionIndexRange(AllowedDirection.BOTH), Integer.MAX_VALUE,
                new ClosestTapPositionFinder(r1));
        continuousR1 = r1;
        r1 = Double.NaN;
    }

    @Override
    public boolean shiftOneTapPositionToChangeA1(Direction direction) {
        // an increase direction means that A1 should increase.
        // a decrease direction means that A1 should decrease.
        double currentA1 = getA1();
        int oldTapPositionIndex = tapPositionIndex;

        if (tapPositionIndex < models.size() - 1) {
            double nextA1 = models.get(tapPositionIndex + 1).getA1(); // abs?
            if (direction == Direction.INCREASE && nextA1 > currentA1
                    || direction == Direction.DECREASE && nextA1 < currentA1) {
                tapPositionIndex++;
            }
        }

        if (tapPositionIndex > 0) {
            double previousA1 = models.get(tapPositionIndex - 1).getA1(); // abs?
            if (direction == Direction.INCREASE && previousA1 > currentA1
                    || direction == Direction.DECREASE && previousA1 < currentA1) {
                tapPositionIndex--;
            }
        }

        if (tapPositionIndex != oldTapPositionIndex) {
            a1 = Double.NaN;
            for (LfNetworkListener listener : branch.getNetwork().getListeners()) {
                listener.onTapPositionChange(branch, lowTapPosition + oldTapPositionIndex, lowTapPosition + tapPositionIndex);
            }
            return true;
        }

        return false;
    }

    @Override
    public Optional<Direction> updateTapPositionToReachNewR1(double deltaR1, int maxTapShift, AllowedDirection allowedDirection) {
        double newR1 = getR1() + deltaR1;
        Range<Integer> positionIndexRange = getAllowedPositionIndexRange(allowedDirection);
        Optional<Direction> direction = updateTapPosition(PiModel::getR1, positionIndexRange, maxTapShift,
                new ClosestTapPositionFinder(newR1));
        if (direction.isPresent()) {
            r1 = Double.NaN;
        }
        return direction;
    }

    @Override
    public Optional<Direction> updateTapPositionToExceedNewA1(double deltaA1, int maxTapShift, AllowedDirection allowedDirection) {
        Range<Integer> positionIndexRange = getAllowedPositionIndexRange(allowedDirection);
        Optional<Direction> direction = updateTapPosition(PiModel::getA1, positionIndexRange, maxTapShift,
                new FirstTapPositionAboveFinder(deltaA1));
        if (direction.isPresent()) {
            a1 = Double.NaN;
        }
        return direction;
    }

    @Override
    public Optional<Direction> updateTapPositionToReachNewA1(double deltaA1, int maxTapShift, AllowedDirection allowedDirection) {
        double newA1 = getA1() + deltaA1;
        Range<Integer> positionIndexRange = getAllowedPositionIndexRange(allowedDirection);
        Optional<Direction> direction = updateTapPosition(PiModel::getA1, positionIndexRange, maxTapShift,
                new ClosestTapPositionFinder(newA1));
        if (direction.isPresent()) {
            a1 = Double.NaN;
        }
        return direction;
    }

    @Override
    public Optional<Complex> getCutZ(double minZ, LoadFlowModel loadFlowModel) {
        return getModel().getCutZ(minZ, loadFlowModel);
    }

    @Override
    public boolean setMinZ(double minZ, LoadFlowModel loadFlowModel) {
        boolean done = false;
        for (PiModel model : models) {
            done |= model.setMinZ(minZ, loadFlowModel);
        }
        return done;
    }

    @Override
    public void setBranch(LfBranch branch) {
        this.branch = Objects.requireNonNull(branch);
    }

    @Override
    public int getTapPosition() {
        return lowTapPosition + tapPositionIndex;
    }

    @Override
    public PiModel setTapPosition(int tapPosition) {
        Range<Integer> tapPositionRange = getTapPositionRange();
        if (!tapPositionRange.contains(tapPosition)) {
            throw new IllegalArgumentException("Tap position " + tapPosition + " out of range " + tapPositionRange);
        }
        if (tapPosition - lowTapPosition != tapPositionIndex) {
            int oldTapPositionIndex = tapPositionIndex;
            tapPositionIndex = tapPosition - lowTapPosition;
            r1 = Double.NaN;
            continuousR1 = Double.NaN;
            a1 = Double.NaN;
            for (LfNetworkListener listener : branch.getNetwork().getListeners()) {
                listener.onTapPositionChange(branch, lowTapPosition + oldTapPositionIndex, tapPosition);
            }
        }
        return this;
    }

    @Override
    public double getMinR1() {
        return minR1;
    }

    @Override
    public double getMaxR1() {
        return maxR1;
    }

    @Override
    public Range<Integer> getTapPositionRange() {
        return Range.of(lowTapPosition, lowTapPosition + models.size() - 1);
    }
}
