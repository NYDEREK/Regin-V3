/*
 * Line_Follower.c
 *
 *  Created on: Jun 7, 2025
 *      Author: SNYDE
 */
#include "main.h"
#include "Line_Follower.h"
#include "tim.h"

extern LineFollower_t GRUZIK;

#define PI_MOTOR_SPEED_REGULATION 1

/*SensorArray index (mux channel order from SensorRead() in main.c) of every sensor,
 * from the robot's left to its right, looking in the driving direction*/
const uint8_t LF_SensorOrder[LF_SENSOR_COUNT] = {13, 5, 9, 1, 12, 4, 8, 0, 15, 7, 11, 3, 14, 6, 10, 2};

/*Line position: sensor k (1 = left .. 16 = right) on the line weighs k * 1000,
 * so the centre of the bar is LF_POSITION_CENTER (8500)*/
static int SensorRead(LineFollower_t *LF)
{
	int pos = 0;
	int active = 0;

	for (uint8_t i = 0; i < LF_SENSOR_COUNT; i++)
	{
		if (LF->SensorArray[LF_SensorOrder[i]] > LF->treshold)
		{
			pos += (i + 1) * 1000;
			active++;
		}
	}

	/*Last edge that saw the line, sharp_turn() searches on that side: 0 = left, 1 = right*/
	if ((LF->SensorArray[LF_SensorOrder[0]] > LF->treshold) && (HAL_GetTick() > (LF->LastEndTimer + 1)))
	{
		LF->LastEndTimer = HAL_GetTick();
		LF->Last_end = 0;
	}
	if ((LF->SensorArray[LF_SensorOrder[LF_SENSOR_COUNT - 1]] > LF->treshold) && (HAL_GetTick() > (LF->LastEndTimer + 1)))
	{
		LF->LastEndTimer = HAL_GetTick();
		LF->Last_end = 1;
	}

	LF->actives = active;

	if (LF->actives == 0)
	{
		LF->Last_idle++;
	}
	else
	{
		LF->Last_idle = 0;
	}
	if(active != 0)
	{
		LF->SensorPosition = pos/active;
	}
	else
	{
		if(LF->Last_end == 1)
		{
			LF->SensorPosition = LF_SENSOR_COUNT * 1000;
		}
		else
		{
			LF->SensorPosition = 1000;
		}
	}

	return LF->SensorPosition;
}
int LineFollower_UpdatePosition(LineFollower_t *LF)
{
	return SensorRead(LF);
}
void motor_control(LineFollower_t* LF, float pos_right, float pos_left)
{
	if(pos_left < 0)
	{
		__HAL_TIM_SET_COMPARE(&htim5, TIM_CHANNEL_2, 0);//LEWY przod
		__HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_1,(uint32_t)( -1 * pos_left));//LEWY Tyl
//		LF->PWML_FRONT = __HAL_TIM_GET_COMPARE(&htim5, TIM_CHANNEL_2);
//		LF->PWML_BACK = __HAL_TIM_GET_COMPARE(&htim2, TIM_CHANNEL_1);
	}
	else
	{
		__HAL_TIM_SET_COMPARE(&htim2, TIM_CHANNEL_1, 0);//LEWY Tyl
		__HAL_TIM_SET_COMPARE(&htim5, TIM_CHANNEL_2,  (uint32_t)(pos_left));//LEWY przod

	}
	if(pos_right < 0)
	{
		__HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_2, 0);//PRAWY_przod
		__HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_1, (uint32_t)(-1 * pos_right));//PRAWY Tyl
//		LF->PWMR_FRONT = __HAL_TIM_GET_COMPARE(&htim3, TIM_CHANNEL_2);
//		LF->PWMR_BACK = __HAL_TIM_GET_COMPARE(&htim3, TIM_CHANNEL_1);

	}
	else
	{
		__HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_1, 0);//PRAWY Tyl
		__HAL_TIM_SET_COMPARE(&htim3, TIM_CHANNEL_2, (uint32_t)(pos_right));//PRAWY_przod
	}
}
/*Line lost: spin towards the edge that saw it last.
 * Sharp_bend_speed_left / Bend_speed_left drive the outer wheel,
 * Sharp_bend_speed_right / Bend_speed_right the inner wheel*/
void sharp_turn(LineFollower_t *LF)
{

	if (LF->Last_idle < 25)
	{
		if (LF->Last_end == 1)
		{
			motor_control(&GRUZIK, GRUZIK.Sharp_bend_speed_right, GRUZIK.Sharp_bend_speed_left);
		}
		else
		{
			motor_control(&GRUZIK, GRUZIK.Sharp_bend_speed_left, GRUZIK.Sharp_bend_speed_right);
		}
	}
	else
	{
		if (LF->Last_end == 1)
		{
			motor_control(&GRUZIK, GRUZIK.Bend_speed_right, GRUZIK.Bend_speed_left);
		}
		else
		{
			motor_control(&GRUZIK, GRUZIK.Bend_speed_left, GRUZIK.Bend_speed_right);
		}
	}
}
void forward_brake(LineFollower_t *LF, float pos_right, float pos_left)
{
	if (LF->actives == 0)
	{
		sharp_turn(&GRUZIK);
	}
	else
	{
	  motor_control(&GRUZIK, pos_right, pos_left);
	}
}
void past_errors (LineFollower_t *LF, int error)
{
  for (int i = 9; i > 0; i--)
      LF->Errors[i] = LF->Errors[i-1];
  	  LF->Errors[0] = error;
}
void PID_control(LineFollower_t *LF)
{

  uint16_t position = SensorRead(LF);
  /*Positive error = line right of the centre -> left motor faster -> turn right*/
  float error = (float)position - LF_POSITION_CENTER;
  //int errordif = error - LF->Last_error;


  LF->P = error;
  LF->Error_P = error;
  LF->D = error - LF->Last_error;
  LF->Error_D = error - LF->Last_error;
  LF->Last_error = error;
  //LF->Last_error = error;

//  float motorspeed = LF->P*LF->Kp + LF->D*LF->Kd;
//
//  float motorspeedl = LF->Base_speed_L + motorspeed;
//  float motorspeedr = LF->Base_speed_R - motorspeed;

  int motorspeedl = LF->Base_speed_L + (LF->Kp*error) + (LF->Kd*LF->Error_D);
  int motorspeedr = LF->Base_speed_R - (LF->Kp*error) - (LF->Kd*LF->Error_D);

  if (motorspeedl > LF->Max_speed_L)
    motorspeedl = LF->Max_speed_L;
  if (motorspeedr > LF->Max_speed_R)
    motorspeedr = LF->Max_speed_R;

  forward_brake(LF, motorspeedr, motorspeedl);
}

