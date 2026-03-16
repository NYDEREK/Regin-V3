/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.h
  * @brief          : Header for main.c file.
  *                   This file contains the common defines of the application.
  ******************************************************************************
  * @attention
  *
  * Copyright (c) 2025 STMicroelectronics.
  * All rights reserved.
  *
  * This software is licensed under terms that can be found in the LICENSE file
  * in the root directory of this software component.
  * If no LICENSE file comes with this software, it is provided AS-IS.
  *
  ******************************************************************************
  */
/* USER CODE END Header */

/* Define to prevent recursive inclusion -------------------------------------*/
#ifndef __MAIN_H
#define __MAIN_H

#ifdef __cplusplus
extern "C" {
#endif

/* Includes ------------------------------------------------------------------*/
#include "stm32g4xx_hal.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */

/* USER CODE END Includes */

/* Exported types ------------------------------------------------------------*/
/* USER CODE BEGIN ET */

/* USER CODE END ET */

/* Exported constants --------------------------------------------------------*/
/* USER CODE BEGIN EC */

/* USER CODE END EC */

/* Exported macro ------------------------------------------------------------*/
/* USER CODE BEGIN EM */

/* USER CODE END EM */

/* Exported functions prototypes ---------------------------------------------*/
void Error_Handler(void);

/* USER CODE BEGIN EFP */
void delay_us (uint16_t us);
/* USER CODE END EFP */

/* Private defines -----------------------------------------------------------*/
#define E_Pin GPIO_PIN_1
#define E_GPIO_Port GPIOC
#define S3_Pin GPIO_PIN_2
#define S3_GPIO_Port GPIOC
#define S2_Pin GPIO_PIN_3
#define S2_GPIO_Port GPIOC
#define S1_Pin GPIO_PIN_0
#define S1_GPIO_Port GPIOA
#define S0_Pin GPIO_PIN_1
#define S0_GPIO_Port GPIOA
#define Z_Pin GPIO_PIN_2
#define Z_GPIO_Port GPIOA
#define ADC1_Battery_Pin GPIO_PIN_3
#define ADC1_Battery_GPIO_Port GPIOA
#define LED2_Pin GPIO_PIN_4
#define LED2_GPIO_Port GPIOA
#define PWM2_R_Pin GPIO_PIN_6
#define PWM2_R_GPIO_Port GPIOA
#define PWM1_R_Pin GPIO_PIN_7
#define PWM1_R_GPIO_Port GPIOA
#define INH_Pin GPIO_PIN_9
#define INH_GPIO_Port GPIOC
#define PWM2_L_Pin GPIO_PIN_15
#define PWM2_L_GPIO_Port GPIOA
#define PWM1_L_Pin GPIO_PIN_12
#define PWM1_L_GPIO_Port GPIOC
#define PWM_T_TIM16_Pin GPIO_PIN_4
#define PWM_T_TIM16_GPIO_Port GPIOB
#define LED1_Pin GPIO_PIN_8
#define LED1_GPIO_Port GPIOB

/* USER CODE BEGIN Private defines */

/* USER CODE END Private defines */

#ifdef __cplusplus
}
#endif

#endif /* __MAIN_H */
