/*
 * robot_config.h
 *
 * Hardware constants for REGIN V3 and the Bluetooth link
 * used by the GRUZIK4.0 Android app.
 */

#ifndef INC_ROBOT_CONFIG_H_
#define INC_ROBOT_CONFIG_H_

#define ROBOT_NAME                     "REGIN_V3"

/*Motors: BTN9970 half bridges, TIM2/TIM3/TIM5 period 999*/
#define ROBOT_PWM_MAX                  999.0f
#define ROBOT_MANUAL_TIMEOUT_MS        350u
#define ROBOT_CLEAN_SPEED_DEFAULT      170.0f
#define ROBOT_CLEAN_SPEED_MAX          250.0f

/*Turbine ESC on TIM16, 1 tick = 1 us*/
#define ROBOT_ESC_MIN_US               1000u
#define ROBOT_ESC_MAX_US               2000u
#define ROBOT_TURBINE_PREP_DEFAULT_MS  1000u

/*Battery: 3S LiPo on ADC1_IN4*/
#define ROBOT_BATTERY_ADC_SCALE        (11.39f / 2535.0f)
#define ROBOT_BATTERY_CELLS            3.0f
#define ROBOT_BATTERY_FULL_V           13.0f
#define ROBOT_BATTERY_MIN_START_V      11.0f

/*Line sensor telemetry (DBG lines), only sent while the robot is stopped*/
#define ROBOT_TELEMETRY_PERIOD_MS      250u

/*Bluetooth module*/
#define ROBOT_BT_DATA_BAUD             9600u
#define ROBOT_BT_AT_BAUD_PRIMARY       9600u
#define ROBOT_BT_AT_BAUD_SECONDARY     38400u
#define ROBOT_BT_AT_TIMEOUT_MS         220u
#define ROBOT_BT_NAME_MAX_LEN          20u

#endif /* INC_ROBOT_CONFIG_H_ */
