package com.hansul.recipe.video.upload;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs {@link VideoUploadSweeper}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class VideoUploadSchedulingConfiguration {
}
