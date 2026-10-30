/*
 * SimpleParser.c
 *
 *  Created on: Aug 28, 2024
 *      Author: Szymon
 *
 * UART (Bluetooth) command parser for REGIN V3.
 * Protocol is the same as GRUZIK4.0, so the GRUZIK4.0 Android app
 * can drive this robot. REGIN V3 has no encoders, IMU or SD card, so
 * odometry, mapping and map transfer commands answer with an error
 * line instead of doing anything.
 */


#include "main.h"
#include "gpio.h"
#include "usart.h"
#include "string.h"
#include "stdio.h"
#include "math.h"
#include "RingBuffer.h"
#include "stdlib.h"
#include "SimpleParser.h"
#include "Line_Follower.h"
#include "robot_config.h"

extern uint8_t RxData;

volatile uint8_t TelemetryMode = TELEMETRY_OFF;
volatile uint8_t TireCleaningActive = 0u;
volatile uint8_t ManualDriveActive = 0u;

static float TireCleanSpeed = ROBOT_CLEAN_SPEED_DEFAULT;
static volatile uint32_t ManualDriveLastTick = 0u;

/*Sensor indexes from left to right, same order as SensorRead() in Line_Follower.c*/
static const uint8_t SensorOrder[16] = {2, 10, 6, 14, 3, 11, 7, 15, 0, 8, 4, 12, 1, 9, 5, 13};

static void UartSend(const char *text)
{
	HAL_UART_Transmit(&huart1, (uint8_t *)text, strlen(text), 500);
}

static void SendMappingUnsupported(void)
{
	UartSend("MAP_ERROR,unsupported,no_odometry_sd\r\n");
}

static float ReadBatteryVoltage(LineFollower_t *LF)
{
	LF->battery_voltage = (float)LF->Adc1_Values[1] * ROBOT_BATTERY_ADC_SCALE;
	return LF->battery_voltage;
}

static float ClampPwm(float value)
{
	if (value > ROBOT_PWM_MAX)
	{
		return ROBOT_PWM_MAX;
	}
	if (value < -ROBOT_PWM_MAX)
	{
		return -ROBOT_PWM_MAX;
	}
	return value;
}

static char *NextValue(void)
{
	return strtok(NULL, ",");
}

static uint8_t ParseFloatValue(float *out)
{
	char *ParsePointer = NextValue();

	if ((ParsePointer == NULL) || (strlen(ParsePointer) == 0u) || (strlen(ParsePointer) >= 32u))
	{
		return 0u;
	}

	*out = strtof(ParsePointer, NULL);
	return 1u;
}

/*Bluetooth module rename (AT commands)*/
static void TrimText(char *text)
{
	size_t len = strlen(text);
	while ((len > 0u) && ((text[len - 1u] == ' ') || (text[len - 1u] == '\t') ||
						  (text[len - 1u] == '\r') || (text[len - 1u] == '\n')))
	{
		text[len - 1u] = '\0';
		len--;
	}

	char *start = text;
	while ((*start == ' ') || (*start == '\t'))
	{
		start++;
	}

	if (start != text)
	{
		memmove(text, start, strlen(start) + 1u);
	}
}

static uint8_t BluetoothNameIsValid(const char *name)
{
	size_t len = strlen(name);
	if ((len == 0u) || (len > ROBOT_BT_NAME_MAX_LEN))
	{
		return 0u;
	}

	for (size_t i = 0u; i < len; i++)
	{
		char c = name[i];
		uint8_t allowed = (((c >= 'A') && (c <= 'Z')) ||
						   ((c >= 'a') && (c <= 'z')) ||
						   ((c >= '0') && (c <= '9')) ||
						   (c == ' ') || (c == '_') || (c == '-') || (c == '.'));
		if (allowed == 0u)
		{
			return 0u;
		}
	}

	return 1u;
}

static uint8_t ReadBluetoothNameArgument(char *name, size_t name_size)
{
	char *value = strtok(NULL, "\r\n");
	if (value == NULL)
	{
		UartSend("BT_NAME_ERROR,empty\r\n");
		return 0u;
	}

	snprintf(name, name_size, "%s", value);
	TrimText(name);
	if (BluetoothNameIsValid(name) == 0u)
	{
		UartSend("BT_NAME_ERROR,bad_name\r\n");
		return 0u;
	}

	return 1u;
}

static void BluetoothSetBaud(uint32_t baud)
{
	if (huart1.Init.BaudRate == baud)
	{
		return;
	}

	(void)HAL_UART_DeInit(&huart1);
	huart1.Init.BaudRate = baud;
	(void)HAL_UART_Init(&huart1);
}

static uint8_t BluetoothResponseOk(const char *response)
{
	return ((strstr(response, "OK") != NULL) ||
			(strstr(response, "Ok") != NULL) ||
			(strstr(response, "ok") != NULL)) ? 1u : 0u;
}

static uint8_t BluetoothSendAtCommand(const char *command, char *response, size_t response_size)
{
	memset(response, 0, response_size);
	(void)HAL_UART_Transmit(&huart1, (uint8_t *)command, (uint16_t)strlen(command), 300);

	uint32_t start_ms = HAL_GetTick();
	size_t used = 0u;
	while (((HAL_GetTick() - start_ms) < ROBOT_BT_AT_TIMEOUT_MS) && (used < (response_size - 1u)))
	{
		uint8_t byte = 0u;
		if (HAL_UART_Receive(&huart1, &byte, 1u, 10u) == HAL_OK)
		{
			response[used++] = (char)byte;
			response[used] = '\0';
			if ((BluetoothResponseOk(response) != 0u) || (strstr(response, "ERROR") != NULL))
			{
				break;
			}
		}
	}

	return BluetoothResponseOk(response);
}

static uint8_t BluetoothTryRenameAtBaud(uint32_t baud, const char *name)
{
	char response[96];
	char command[48];

	BluetoothSetBaud(baud);
	HAL_Delay(80u);

	if ((BluetoothSendAtCommand("AT\r\n", response, sizeof(response)) == 0u) &&
		(BluetoothSendAtCommand("AT", response, sizeof(response)) == 0u))
	{
		return 0u;
	}

	snprintf(command, sizeof(command), "AT+NAME=%s\r\n", name);
	if (BluetoothSendAtCommand(command, response, sizeof(response)) != 0u)
	{
		return 1u;
	}

	snprintf(command, sizeof(command), "AT+NAME%s\r\n", name);
	if (BluetoothSendAtCommand(command, response, sizeof(response)) != 0u)
	{
		return 1u;
	}

	snprintf(command, sizeof(command), "AT+NAME%s", name);
	return BluetoothSendAtCommand(command, response, sizeof(response));
}

static uint8_t BluetoothApplyName(const char *name, uint32_t *used_baud)
{
	uint8_t ok = 0u;

	(void)HAL_UART_AbortReceive_IT(&huart1);
	HAL_Delay(20u);

	ok = BluetoothTryRenameAtBaud(ROBOT_BT_AT_BAUD_PRIMARY, name);
	if (ok != 0u)
	{
		*used_baud = ROBOT_BT_AT_BAUD_PRIMARY;
	}
	else
	{
		ok = BluetoothTryRenameAtBaud(ROBOT_BT_AT_BAUD_SECONDARY, name);
		if (ok != 0u)
		{
			*used_baud = ROBOT_BT_AT_BAUD_SECONDARY;
		}
	}

	BluetoothSetBaud(ROBOT_BT_DATA_BAUD);
	HAL_Delay(20u);
	(void)HAL_UART_Receive_IT(&huart1, &RxData, 1u);

	return ok;
}

void Parser_TakeLine(RingBuffer_t *Buf, uint8_t *ReceivedData)
{
	uint8_t Tmp = 0u;
	uint8_t i = 0u;

	do
	{
		if (RB_Read(Buf, &Tmp) != RB_OK)
		{
			ReceivedData[i] = 0u;
			return;
		}

		if (Tmp == ENDLINE)
		{
			ReceivedData[i] = 0u;
		}
		else if ((Tmp != '\r') && (i < (PARSER_LINE_BUFFER_SIZE - 1u)))
		{
			ReceivedData[i++] = Tmp;
		}
	} while (Tmp != ENDLINE);
}

/*PID and speed settings*/
static void Sensor_treshold_change(LineFollower_t *LF)
{
	float treshold = 0.0f;
	if (ParseFloatValue(&treshold) && (treshold > 1500.0f))
	{
		LF->treshold = (uint16_t)treshold;
	}
}

static void Turbine_Speed_change(LineFollower_t *LF)
{
	/*App sends 0..1000, ESC takes 1000..2000 us*/
	float speed = 0.0f;
	if (ParseFloatValue(&speed))
	{
		if (speed < 0.0f)
		{
			speed = 0.0f;
		}
		else if (speed > (float)(ROBOT_ESC_MAX_US - ROBOT_ESC_MIN_US))
		{
			speed = (float)(ROBOT_ESC_MAX_US - ROBOT_ESC_MIN_US);
		}
		LF->Turbine_Speed = (float)ROBOT_ESC_MIN_US + speed;
	}
}

static void Turbine_Prep_Time_change(LineFollower_t *LF)
{
	float prep_time = 0.0f;
	if (ParseFloatValue(&prep_time) && (prep_time >= 0.0f))
	{
		LF->Turbine_Prep_Time = (uint32_t)prep_time;
	}
}

static void kp_change(LineFollower_t *LF)
{
	ParseFloatValue(&LF->Kp);
}

static void kd_change(LineFollower_t *LF)
{
	ParseFloatValue(&LF->Kd);
}

static void Base_speed_change(LineFollower_t *LF)
{
	float speed = 0.0f;
	if (ParseFloatValue(&speed))
	{
		LF->Base_speed_R = speed;
		LF->Base_speed_L = speed;
	}
}

static void Max_speed_change(LineFollower_t *LF)
{
	float speed = 0.0f;
	if (ParseFloatValue(&speed))
	{
		LF->Max_speed_R = speed;
		LF->Max_speed_L = speed;
	}
}

static void Sharp_bend_speed_right_change(LineFollower_t *LF)
{
	ParseFloatValue(&LF->Sharp_bend_speed_right);
}

static void Sharp_bend_speed_left_change(LineFollower_t *LF)
{
	ParseFloatValue(&LF->Sharp_bend_speed_left);
}

static void Bend_speed_right_change(LineFollower_t *LF)
{
	ParseFloatValue(&LF->Bend_speed_right);
}

static void Bend_speed_left_change(LineFollower_t *LF)
{
	ParseFloatValue(&LF->Bend_speed_left);
}

/*Joystick and tire cleaning*/
static void Tire_clean_speed_change(void)
{
	float speed = 0.0f;
	if (ParseFloatValue(&speed))
	{
		if (speed < 0.0f)
		{
			speed = 0.0f;
		}
		else if (speed > ROBOT_CLEAN_SPEED_MAX)
		{
			speed = ROBOT_CLEAN_SPEED_MAX;
		}
		TireCleanSpeed = speed;
	}
}

static void Manual_drive_change(LineFollower_t *LF)
{
	float left_speed = 0.0f;
	float right_speed = 0.0f;
	if (!ParseFloatValue(&left_speed))
	{
		return;
	}
	if (!ParseFloatValue(&right_speed))
	{
		right_speed = left_speed;
	}

	/*Joystick released, TIM20 brakes the motors on the next tick*/
	if ((fabsf(left_speed) < 0.5f) && (fabsf(right_speed) < 0.5f))
	{
		ManualDriveActive = 0u;
		motor_control(LF, 0.0f, 0.0f);
		return;
	}

	if (LF->PowerMode == Start)
	{
		UartSend("MANUAL_ERROR,robot_running\r\n");
		return;
	}

	TireCleaningActive = 0u;
	TelemetryMode = TELEMETRY_OFF;
	LF->LineFollowing = 0u;

	ManualDriveLastTick = HAL_GetTick();
	ManualDriveActive = 1u;
	motor_control(LF, ClampPwm(right_speed), ClampPwm(left_speed));
}

static void Tire_clean_change(LineFollower_t *LF)
{
	float enabled = 0.0f;
	if (!ParseFloatValue(&enabled))
	{
		return;
	}

	if (enabled > 0.0f)
	{
		if (LF->PowerMode == Start)
		{
			UartSend("CLEAN_ERROR,robot_running\r\n");
			return;
		}

		ManualDriveActive = 0u;
		TelemetryMode = TELEMETRY_OFF;
		LF->LineFollowing = 0u;

		TireCleaningActive = 1u;
		motor_control(LF, TireCleanSpeed, TireCleanSpeed);
		UartSend("CLEAN,start\r\n");
	}
	else
	{
		TireCleaningActive = 0u;
		motor_control(LF, 0.0f, 0.0f);
		UartSend("CLEAN,stop\r\n");
	}
}

void Parser_ServiceManualDriveTimeout(LineFollower_t *LF)
{
	/*No joystick packets from the app, stop manual drive*/
	if ((ManualDriveActive != 0u) && ((HAL_GetTick() - ManualDriveLastTick) > ROBOT_MANUAL_TIMEOUT_MS))
	{
		ManualDriveActive = 0u;
		motor_control(LF, 0.0f, 0.0f);
	}
}

/*Bluetooth name*/
static void Bluetooth_name_store(void)
{
	/*GRUZIK4.0 keeps the pending name on SD, REGIN V3 has no SD card*/
	UartSend("BT_NAME_ERROR,no_sd_use_try_now\r\n");
}

static void Bluetooth_name_now(LineFollower_t *LF)
{
	char name[ROBOT_BT_NAME_MAX_LEN + 1u];
	uint32_t baud = 0u;

	if (LF->PowerMode == Start)
	{
		UartSend("BT_NAME_ERROR,stop_robot_first\r\n");
		return;
	}

	if (ReadBluetoothNameArgument(name, sizeof(name)) == 0u)
	{
		return;
	}

	LF->LineFollowing = 0u;
	TelemetryMode = TELEMETRY_OFF;
	TireCleaningActive = 0u;
	ManualDriveActive = 0u;
	motor_control(LF, 0.0f, 0.0f);

	if (BluetoothApplyName(name, &baud) != 0u)
	{
		char tx[72];
		snprintf(tx, sizeof(tx), "BT_NAME_OK,%s,%lu\r\n", name, (unsigned long)baud);
		UartSend(tx);
	}
	else
	{
		UartSend("BT_NAME_ERROR,at_no_response\r\n");
	}
}

/*Telemetry*/
static void Telemetry_change(LineFollower_t *LF)
{
	char *ParsePointer = NextValue();
	if (ParsePointer == NULL)
	{
		return;
	}

	if ((!strcmp(ParsePointer, "debug")) || (!strcmp(ParsePointer, "DEBUG")) || (!strcmp(ParsePointer, "2")))
	{
		/*Sending DBG lines blocks the main loop, where the sensors are read*/
		if (LF->PowerMode == Start)
		{
			TelemetryMode = TELEMETRY_OFF;
			UartSend("TELEMETRY,off,stop_robot_first\r\n");
			return;
		}
		TelemetryMode = TELEMETRY_DEBUG;
		UartSend("TELEMETRY,debug\r\n");
	}
	else if ((!strcmp(ParsePointer, "odom")) || (!strcmp(ParsePointer, "ODOM")) || (!strcmp(ParsePointer, "1")))
	{
		TelemetryMode = TELEMETRY_OFF;
		UartSend("TELEMETRY,odom_unsupported\r\n");
	}
	else
	{
		TelemetryMode = TELEMETRY_OFF;
		UartSend("TELEMETRY,off\r\n");
	}
}

void Parser_ServiceTelemetry(LineFollower_t *LF)
{
	static uint32_t last_telemetry_ms = 0u;

	if ((TelemetryMode != TELEMETRY_DEBUG) || (LF->PowerMode == Start))
	{
		return;
	}

	uint32_t now = HAL_GetTick();
	if ((now - last_telemetry_ms) < ROBOT_TELEMETRY_PERIOD_MS)
	{
		return;
	}
	last_telemetry_ms = now;

	(void)LineFollower_UpdatePosition(LF);

	/*Same layout as GRUZIK4.0: pos, actives, last end, encoder and IMU fields (none here), sensors*/
	char tx[192];
	int len = snprintf(tx, sizeof(tx), "DBG,%d,%d,%d,0,0,0.0,0.0,0.0000,0.0000,0.00,0.00,0.00",
					   LF->SensorPosition, LF->actives, LF->Last_end);

	for (uint8_t i = 0u; (i < 16u) && (len > 0) && (len < (int)sizeof(tx)); i++)
	{
		len += snprintf(&tx[len], sizeof(tx) - (size_t)len, ",%u", (unsigned int)LF->SensorArray[SensorOrder[i]]);
	}

	if ((len > 0) && (len < ((int)sizeof(tx) - 2)))
	{
		tx[len++] = '\r';
		tx[len++] = '\n';
		HAL_UART_Transmit(&huart1, (uint8_t *)tx, (uint16_t)len, 500);
	}
}

/*Start / stop*/
static void SetState(LineFollower_t *LF, uint8_t state)
{
	if (LF->PowerMode == Start)
	{
		UartSend("Stop robot before changing state\r\n");
		return;
	}

	/*Mapping states are kept, so a following Mode=Y refuses to start instead of driving PID*/
	LF->state = state;
	if (state == PidFollowing)
	{
		UartSend("State: PID\r\n");
	}
	else
	{
		SendMappingUnsupported();
	}
}

static void StopRobot(LineFollower_t *LF)
{
	char buffer[96];

	LF->LineFollowing = 0u;
	LF->PowerMode = Stop;
	TireCleaningActive = 0u;
	ManualDriveActive = 0u;
	motor_control(LF, 0.0f, 0.0f);

	HAL_GPIO_TogglePin(LED2_GPIO_Port, LED2_Pin);

	ReadBatteryVoltage(LF);
	snprintf(buffer, sizeof(buffer), "Stop\r\nOne Cell = %0.2f\r\nBattery = %0.2f V\r\n",
			 LF->battery_voltage / ROBOT_BATTERY_CELLS, LF->battery_voltage);
	UartSend(buffer);
}

static void PrepareStoppedStart(LineFollower_t *LF)
{
	LF->LineFollowing = 0u;
	LF->PowerMode = Stop;
	TelemetryMode = TELEMETRY_OFF;
	TireCleaningActive = 0u;
	ManualDriveActive = 0u;
	motor_control(LF, 0.0f, 0.0f);
}

static void StartRobot(LineFollower_t *LF)
{
	float battery_percentage;
	char buffer[96];

	if (LF->state != PidFollowing)
	{
		SendMappingUnsupported();
		return;
	}

	ReadBatteryVoltage(LF);

	/*To don't damage 3s LiPo battery Line follower can't start with low battery*/
	if (LF->battery_voltage < ROBOT_BATTERY_MIN_START_V)
	{
		UartSend("! Low Battery !\r\n");
		return;
	}

	/*Proportional to battery percentage boost for motors
	 * to keep roughly same speed as with full battery*/
	battery_percentage = (LF->battery_voltage / ROBOT_BATTERY_FULL_V) * 100.0f;
	LF->Speed_level = ((200.0f - battery_percentage) / 100.0f) - LF->Speed_offset;
	if (LF->Speed_level < 1.0f)
	{
		LF->Speed_level = 1.0f;
	}

	TelemetryMode = TELEMETRY_OFF;
	TireCleaningActive = 0u;
	ManualDriveActive = 0u;

	LF->Last_error = 0.0f;
	LF->P = 0.0f;
	LF->D = 0.0f;
	LF->Error_P = 0.0f;
	LF->Error_D = 0.0f;
	LF->Last_idle = 0;
	LF->LastEndTimer = HAL_GetTick();

	snprintf(buffer, sizeof(buffer), "Start\r\nBattery = %0.2f V\r\nSpeed_level = %0.2f\r\n",
			 LF->battery_voltage, LF->Speed_level);
	UartSend(buffer);

	/*Start LineFollower and turn on the LED*/
	HAL_GPIO_TogglePin(LED2_GPIO_Port, LED2_Pin);
	LF->PowerMode = Start;
}

static void StartWithState(LineFollower_t *LF, uint8_t state)
{
	PrepareStoppedStart(LF);
	LF->state = state;

	if (state != PidFollowing)
	{
		SendMappingUnsupported();
		return;
	}

	UartSend("STARTING,normal\r\n");
	StartRobot(LF);
}

static void App_Controll(char command, LineFollower_t *LF)
{
	if (command == 'N')
	{
		StopRobot(LF);
	}
	else if ((command == 'Y') || (command == 'C'))
	{
		if (LF->PowerMode != Start)
		{
			StartRobot(LF);
		}
		else
		{
			UartSend("Already started\r\n");
		}
	}
	else if (command == 'P')
	{
		SetState(LF, PidFollowing);
	}
	else if (command == 'M')
	{
		SetState(LF, Mapping);
	}
	else if (command == 'U')
	{
		SetState(LF, UnMapping);
	}
}

static void Start_command(LineFollower_t *LF, uint8_t state)
{
	(void)NextValue();
	StartWithState(LF, state);
}

static void Mode_change(LineFollower_t *LF)
{
	char *ParsePointer = NextValue();

	if ((ParsePointer != NULL) && (strlen(ParsePointer) > 0u))
	{
		App_Controll(ParsePointer[0], LF);
	}
}

static void State_change(LineFollower_t *LF)
{
	char *ParsePointer = NextValue();
	if (ParsePointer == NULL)
	{
		return;
	}

	if ((!strcmp(ParsePointer, "PID")) || (!strcmp(ParsePointer, "Pid")) || (!strcmp(ParsePointer, "P")))
	{
		SetState(LF, PidFollowing);
	}
	else if ((!strcmp(ParsePointer, "Mapping")) || (!strcmp(ParsePointer, "M")))
	{
		SetState(LF, Mapping);
	}
	else if ((!strcmp(ParsePointer, "UnMapping")) || (!strcmp(ParsePointer, "U")))
	{
		SetState(LF, UnMapping);
	}
}

void Parser_Parse(uint8_t *ReceivedData, LineFollower_t *LineFollower)
{
	char *ParsePointer = strtok((char*)ReceivedData, "=");
	if (ParsePointer == NULL)
	{
		return;
	}

	if(!strcmp("Kp",ParsePointer))
	{
		kp_change(LineFollower);
	}
	else if(!strcmp("Kd",ParsePointer))
	{
		kd_change(LineFollower);
	}
	else if(!strcmp("Base_speed",ParsePointer))
	{
		Base_speed_change(LineFollower);
	}
	else if(!strcmp("Max_speed",ParsePointer))
	{
		Max_speed_change(LineFollower);
	}
	else if(!strcmp("Sharp_bend_speed_right",ParsePointer))
	{
		Sharp_bend_speed_right_change(LineFollower);
	}
	else if(!strcmp("Sharp_bend_speed_left",ParsePointer))
	{
		Sharp_bend_speed_left_change(LineFollower);
	}
	else if(!strcmp("Bend_speed_right",ParsePointer))
	{
		Bend_speed_right_change(LineFollower);
	}
	else if(!strcmp("Bend_speed_left",ParsePointer))
	{
		Bend_speed_left_change(LineFollower);
	}
	else if((!strcmp("Turbine_Speed",ParsePointer)) || (!strcmp("Turbine_speed",ParsePointer)))
	{
		Turbine_Speed_change(LineFollower);
	}
	else if(!strcmp("Turbine_Prep_Time",ParsePointer))
	{
		Turbine_Prep_Time_change(LineFollower);
	}
	else if(!strcmp("Treshold",ParsePointer))
	{
		Sensor_treshold_change(LineFollower);
	}
	else if(!strcmp("Mode",ParsePointer))
	{
		Mode_change(LineFollower);
	}
	else if(!strcmp("State",ParsePointer))
	{
		State_change(LineFollower);
	}
	else if(!strcmp("StartNormal",ParsePointer))
	{
		Start_command(LineFollower, PidFollowing);
	}
	else if(!strcmp("StartMapping",ParsePointer))
	{
		Start_command(LineFollower, Mapping);
	}
	else if(!strcmp("StartPlayback",ParsePointer))
	{
		Start_command(LineFollower, UnMapping);
	}
	else if((!strcmp("Manual",ParsePointer)) || (!strcmp("Joystick",ParsePointer)))
	{
		Manual_drive_change(LineFollower);
	}
	else if((!strcmp("CleanSpeed",ParsePointer)) || (!strcmp("TireCleanSpeed",ParsePointer)))
	{
		Tire_clean_speed_change();
	}
	else if((!strcmp("Clean",ParsePointer)) || (!strcmp("TireClean",ParsePointer)))
	{
		Tire_clean_change(LineFollower);
	}
	else if((!strcmp("Telemetry",ParsePointer)) || (!strcmp("Debug",ParsePointer)))
	{
		Telemetry_change(LineFollower);
	}
	else if((!strcmp("BtName",ParsePointer)) || (!strcmp("BTName",ParsePointer)) ||
			(!strcmp("BluetoothName",ParsePointer)))
	{
		Bluetooth_name_store();
	}
	else if((!strcmp("BtNameNow",ParsePointer)) || (!strcmp("BTNameNow",ParsePointer)) ||
			(!strcmp("BluetoothNameNow",ParsePointer)))
	{
		Bluetooth_name_now(LineFollower);
	}
	else if(!strcmp("MapDump",ParsePointer))
	{
		SendMappingUnsupported();
	}
	else if((!strcmp("MapUploadBegin",ParsePointer)) || (!strcmp("MapUploadEnd",ParsePointer)))
	{
		UartSend("UPLOAD_ERROR,unsupported\r\n");
	}
	/*MapP, MapI, MapD, MapSpeed, MapPoint and Add_Simple_Map_Point
	 * need odometry, they are ignored without a reply*/
}
