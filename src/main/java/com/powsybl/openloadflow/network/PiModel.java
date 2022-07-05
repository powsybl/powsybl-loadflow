/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network;

import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public interface PiModel {

    double A2 = 0;

    double R2 = 1;

    double getR();

    PiModel setR(double r);

    double getX();

    PiModel setX(double x);

    double getZ();

    double getY();

    double getKsi();

    double getG1();

    double getB1();

    double getG2();

    double getB2();

    double getR1();

    double getContinuousR1();

    double getA1();

    PiModel setA1(double a1);

    PiModel setR1(double r1);

    void roundA1ToClosestTap();

    void roundR1ToClosestTap();

    enum Direction {
        INCREASE(AllowedDirection.INCREASE),
        DECREASE(AllowedDirection.DECREASE);

        private final AllowedDirection allowedDirection;

        Direction(AllowedDirection allowedDirection) {
            this.allowedDirection = allowedDirection;
        }

        public AllowedDirection getAllowedDirection() {
            return allowedDirection;
        }
    }

    enum AllowedDirection {
        INCREASE,
        DECREASE,
        BOTH
    }

    boolean updateTapPositionA1(Direction direction);

    Optional<Direction> updateTapPositionR1(double deltaR1, int maxTapIncrement, AllowedDirection allowedDirection);

    boolean setMinZ(double minZ, boolean dc);

    void setBranch(LfBranch branch);
}
