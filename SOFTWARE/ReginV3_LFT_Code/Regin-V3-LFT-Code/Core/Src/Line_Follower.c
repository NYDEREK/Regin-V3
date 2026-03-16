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

static int SensorRead(LineFollower_t *LF)
{
	int pos = 0;
	int active = 0;

	if (LF->SensorArray[2] > LF->treshold)
	{
		pos += 1000;
		active++;
		if(HAL_GetTick() > (LF->LastEndTimer + 1))
		{
 			LF->LastEndTimer = HAL_GetTick();
			LF->Last_end = 0;
		}
	}
	if (LF->SensorArray[10] > LF->treshold)
	{
		pos += 2000;
		active++;
	}
	if (LF->SensorArray[6] > LF->treshold)
	{
		pos += 3000;
		active++;
	}
	if (LF->SensorArray[14] > LF->treshold)
	{
		pos += 4000;
		active++;
	}
	if (LF->SensorArray[3] > LF->treshold)
	{
		pos += 5000;
		active++;
	}
	if (LF->SensorArray[11] > LF->treshold)
	{
		pos += 6000;
		active++;
	}
	if (LF->SensorArray[7] > LF->treshold)
	{
		pos += 7000;
		active++;
	}
	if (LF->SensorArray[15] > LF->treshold)
	{
		pos += 8000;
		active++;
	}
	if (LF->SensorArray[0] > LF->treshold)
	{
		pos += 9000;
		active++;
	}
	if (LF->SensorArray[8] > LF->treshold)
	{
		pos += 10000;
		active++;
	}
	if (LF->SensorArray[4] > LF->treshold)
	{
		pos += 11000;
		active++;
	}
	if (LF->SensorArray[12] > LF->treshold)
	{
		pos += 12000;
		active++;
	}
	if (LF->SensorArray[1] > LF->treshold)
	{
		pos += 13000;
		active++;
	}
	if (LF->SensorArray[9] > LF->treshold)
	{
		pos += 14000;
		active++;
	}
	if (LF->SensorArray[5] > LF->treshold)
	{
		pos += 15000;
		active++;
	}
	if (LF->SensorArray[13] > LF->treshold)
	{
		pos += 16000;
		active++;
		if(HAL_GetTick() > (LF->LastEndTimer + 1))
		{
			LF->LastEndTimer = HAL_GetTick();
			LF->Last_end = 1;
		}
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
			LF->SensorPosition = 16000;
		}
		else
		{
			LF->SensorPosition = 1000;
		}
	}

	return LF->SensorPosition;
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
  float error = 8500 - position;
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

