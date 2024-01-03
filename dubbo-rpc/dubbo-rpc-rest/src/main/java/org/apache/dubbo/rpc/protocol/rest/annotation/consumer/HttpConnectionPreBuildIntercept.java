/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.protocol.rest.annotation.consumer;

import org.apache.dubbo.common.extension.ExtensionScope;
import org.apache.dubbo.common.extension.SPI;

/**
 * http  request build intercept
 */

/*
*  结合服务元数据，对http 请求模板（requestTemplate，包含在httpConnectionCreateContext中）进行填充，包括
*  将 dubbo 中的 attachment 填充进 http header；将 content-type 和 accept 字段加入 header
*  将 dubbo 中的入参填充进 http params、http header、http body 中
*  将 dubbo 中的入参填充进 http path 中（path variable情形）
*  对 http body 按照 Content-type 字段进行编码
* */
@SPI(scope = ExtensionScope.FRAMEWORK)
public interface HttpConnectionPreBuildIntercept {
    void intercept(HttpConnectionCreateContext connectionCreateContext);
}
