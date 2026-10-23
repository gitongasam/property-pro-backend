package com.propertypro.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled jobs (billing, penalties, reminders, notifications).
 * Each job class is switched off with app.jobs.enabled=false (tests, extra instances).
 *
 * Running more than one app instance? Add ShedLock so each job runs once per schedule.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
