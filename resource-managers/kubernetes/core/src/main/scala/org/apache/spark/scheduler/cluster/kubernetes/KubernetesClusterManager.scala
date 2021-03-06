/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.scheduler.cluster.kubernetes

import java.io.File

import io.fabric8.kubernetes.client.Config

import org.apache.spark.SparkContext
import org.apache.spark.deploy.kubernetes.{InitContainerResourceStagingServerSecretPluginImpl, SparkKubernetesClientFactory, SparkPodInitContainerBootstrapImpl}
import org.apache.spark.deploy.kubernetes.config._
import org.apache.spark.deploy.kubernetes.constants._
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.{ExternalClusterManager, SchedulerBackend, TaskScheduler, TaskSchedulerImpl}

private[spark] class KubernetesClusterManager extends ExternalClusterManager with Logging {

  override def canCreate(masterURL: String): Boolean = masterURL.startsWith("k8s")

  override def createTaskScheduler(sc: SparkContext, masterURL: String): TaskScheduler = {
    val scheduler = new KubernetesTaskSchedulerImpl(sc)
    sc.taskScheduler = scheduler
    scheduler
  }

  override def createSchedulerBackend(sc: SparkContext, masterURL: String, scheduler: TaskScheduler)
      : SchedulerBackend = {
    val sparkConf = sc.getConf
    val maybeConfigMap = sparkConf.get(EXECUTOR_INIT_CONTAINER_CONFIG_MAP)
    val maybeConfigMapKey = sparkConf.get(EXECUTOR_INIT_CONTAINER_CONFIG_MAP_KEY)

    val maybeExecutorInitContainerSecretName =
      sparkConf.get(EXECUTOR_INIT_CONTAINER_SECRET)
    val maybeExecutorInitContainerSecretMount =
      sparkConf.get(EXECUTOR_INIT_CONTAINER_SECRET_MOUNT_DIR)
    val executorInitContainerSecretVolumePlugin = for {
      initContainerSecretName <- maybeExecutorInitContainerSecretName
      initContainerSecretMountPath <- maybeExecutorInitContainerSecretMount
    } yield {
      new InitContainerResourceStagingServerSecretPluginImpl(
        initContainerSecretName,
        initContainerSecretMountPath)
    }
    // Only set up the bootstrap if they've provided both the config map key and the config map
    // name. Note that we generally expect both to have been set from spark-submit V2, but for
    // testing developers may simply run the driver JVM locally, but the config map won't be set
    // then.
    val bootStrap = for {
      configMap <- maybeConfigMap
      configMapKey <- maybeConfigMapKey
    } yield {
      new SparkPodInitContainerBootstrapImpl(
        sparkConf.get(INIT_CONTAINER_DOCKER_IMAGE),
        sparkConf.get(DOCKER_IMAGE_PULL_POLICY),
        sparkConf.get(INIT_CONTAINER_JARS_DOWNLOAD_LOCATION),
        sparkConf.get(INIT_CONTAINER_FILES_DOWNLOAD_LOCATION),
        sparkConf.get(INIT_CONTAINER_MOUNT_TIMEOUT),
        configMap,
        configMapKey,
        executorInitContainerSecretVolumePlugin)
    }
    if (maybeConfigMap.isEmpty) {
      logWarning("The executor's init-container config map was not specified. Executors will" +
        " therefore not attempt to fetch remote or submitted dependencies.")
    }
    if (maybeConfigMapKey.isEmpty) {
      logWarning("The executor's init-container config map key was not specified. Executors will" +
        " therefore not attempt to fetch remote or submitted dependencies.")
    }
    val kubernetesClient = SparkKubernetesClientFactory.createKubernetesClient(
        KUBERNETES_MASTER_INTERNAL_URL,
        Some(sparkConf.get(KUBERNETES_NAMESPACE)),
        APISERVER_AUTH_DRIVER_MOUNTED_CONF_PREFIX,
        sparkConf,
        Some(new File(Config.KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH)),
        Some(new File(Config.KUBERNETES_SERVICE_ACCOUNT_CA_CRT_PATH)))
    new KubernetesClusterSchedulerBackend(
      sc.taskScheduler.asInstanceOf[TaskSchedulerImpl], sc, bootStrap, kubernetesClient)
  }

  override def initialize(scheduler: TaskScheduler, backend: SchedulerBackend): Unit = {
    scheduler.asInstanceOf[TaskSchedulerImpl].initialize(backend)
  }
}
