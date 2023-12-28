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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.serialize.SerializationException;
import org.apache.dubbo.common.utils.AtomicPositiveInteger;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.FutureContext;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.InvokeMode;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PAYLOAD;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;

/**
 * DubboInvoker
 */
public class DubboInvoker<T> extends AbstractInvoker<T> {

    private final ClientsProvider clientsProvider;

    private final AtomicPositiveInteger index = new AtomicPositiveInteger();


    private final ReentrantLock destroyLock = new ReentrantLock();

    private final Set<Invoker<?>> invokers;

    private final int serverShutdownTimeout;

    private static final boolean setFutureWhenSync = Boolean.parseBoolean(System.getProperty(CommonConstants.SET_FUTURE_IN_SYNC_MODE, "true"));

    public DubboInvoker(Class<T> serviceType, URL url, ClientsProvider clientsProvider) {
        this(serviceType, url, clientsProvider, null);
    }

    public DubboInvoker(Class<T> serviceType, URL url, ClientsProvider clientsProvider, Set<Invoker<?>> invokers) {// 核心参数是clientsProvider
        super(serviceType, url, new String[]{INTERFACE_KEY, GROUP_KEY, TOKEN_KEY});
        this.clientsProvider = clientsProvider;
        this.invokers = invokers;
        this.serverShutdownTimeout = ConfigurationUtils.getServerShutdownTimeout(getUrl().getScopeModel());
    }

    @Override
    protected Result doInvoke(final Invocation invocation) throws Throwable {// 正式发起调用
        RpcInvocation inv = (RpcInvocation) invocation;
        final String methodName = RpcUtils.getMethodName(invocation);
        inv.setAttachment(PATH_KEY, getUrl().getPath());
        inv.setAttachment(VERSION_KEY, version);
        // 获取发起远程调用的client，类型是ExchangeClient，他是在DubboProtocol.initClient
        ExchangeClient currentClient;
        List<? extends ExchangeClient> exchangeClients = clientsProvider.getClients();
        if (exchangeClients.size() == 1) {
            currentClient = exchangeClients.get(0);
        } else {
            currentClient = exchangeClients.get(index.getAndIncrement() % exchangeClients.size());
        }
        try {
            boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);

            int timeout = RpcUtils.calculateTimeout(getUrl(), invocation, methodName, DEFAULT_TIMEOUT);
            if (timeout <= 0) {
                return AsyncRpcResult.newDefaultAsyncResult(new RpcException(RpcException.TIMEOUT_TERMINATE,
                    "No time left for making the following call: " + invocation.getServiceName() + "."
                        + RpcUtils.getMethodName(invocation) + ", terminate directly."), invocation);
            }

            invocation.setAttachment(TIMEOUT_KEY, String.valueOf(timeout));

            Integer payload = getUrl().getParameter(PAYLOAD, Integer.class);
            // 构造request
            Request request = new Request();
            if (payload != null) {
                request.setPayload(payload);
            }
            request.setData(inv);// 核心，设置invocation，指定要调用provider的哪个服务的哪个方法
            request.setVersion(Version.getProtocolVersion());
            // 基于client发起远程调用
            if (isOneway) {// 没有返回结果的调用，直接把request发送出去就完事了
                boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);
                request.setTwoWay(false);
                currentClient.send(request, isSent);
                return AsyncRpcResult.newDefaultAsyncResult(invocation);
            } else {// 有返回结果的调用，区分同步和异步是在外层AbstractInvoker中完成的
                request.setTwoWay(true);
                /*
                * 获取服务关联的线程池，consumer侧共用一个线程池
                * 如果是同步调用，则是新建的ThreadlessExecutor
                * 如果是异步调用，则是普通的、共用的线程池
                * 为什么要用executor?
                * executor会设置到DefaultFuture，进而被AllChannelHandler使用，用来设置结果 - 根据responseId关联特定的future并唤醒对应的调用线程
                *
                * 为什么要用ThreadlessExecutor而不直接用普通的线程池呢?
                * */
                ExecutorService executor = getCallbackExecutor(getUrl(), inv);
                CompletableFuture<AppResponse> appResponseFuture =
                    currentClient.request(request, timeout, executor).thenApply(AppResponse.class::cast);// 调用request方法并在收到响应后将结果映射成AppResponse，此方法会立即返回 - 这是ExchangeChannel的方法，ExchangeClient集成ExchangeChannel
                // save for 2.6.x compatibility, for example, TraceFilter in Zipkin uses com.alibaba.xxx.FutureAdapter
                if (setFutureWhenSync || ((RpcInvocation) invocation).getInvokeMode() != InvokeMode.SYNC) {
                    FutureContext.getContext().setCompatibleFuture(appResponseFuture);
                }
                AsyncRpcResult result = new AsyncRpcResult(appResponseFuture, inv);// 将future包装进result，AsyncRpcResult能够兼容同步和异步调用
                result.setExecutor(executor);
                return result;
            }
        } catch (TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + RpcUtils.getMethodName(invocation) + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (RemotingException e) {
            String remoteExpMsg = "Failed to invoke remote method: " + RpcUtils.getMethodName(invocation) + ", provider: " + getUrl() + ", cause: " + e.getMessage();
            if (e.getCause() instanceof IOException && e.getCause().getCause() instanceof SerializationException) {
                throw new RpcException(RpcException.SERIALIZATION_EXCEPTION, remoteExpMsg, e);
            } else {
                throw new RpcException(RpcException.NETWORK_EXCEPTION, remoteExpMsg, e);
            }
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable()) {
            return false;
        }
        for (ExchangeClient client : clientsProvider.getClients()) {
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                //cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // in order to avoid closing a client multiple times, a counter is used in case of connection per jvm, every
        // time when client.close() is called, counter counts down once, and when counter reaches zero, client will be
        // closed.
        if (!super.isDestroyed()) {
            // double check to avoid dup close
            destroyLock.lock();
            try {
                if (super.isDestroyed()) {
                    return;
                }
                super.destroy();
                if (invokers != null) {
                    invokers.remove(this);
                }
                clientsProvider.close(ConfigurationUtils.reCalShutdownTime(serverShutdownTimeout));
            } finally {
                destroyLock.unlock();
            }
        }
    }
}
