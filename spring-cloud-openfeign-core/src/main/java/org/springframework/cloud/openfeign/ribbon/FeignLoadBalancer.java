/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign.ribbon;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.netflix.client.AbstractLoadBalancerAwareClient;
import com.netflix.client.ClientException;
import com.netflix.client.ClientRequest;
import com.netflix.client.IResponse;
import com.netflix.client.RequestSpecificRetryHandler;
import com.netflix.client.RetryHandler;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.Server;
import com.netflix.loadbalancer.ServerStats;
import feign.Client;
import feign.Request;
import feign.Response;

import org.springframework.cloud.netflix.ribbon.RibbonProperties;
import org.springframework.cloud.netflix.ribbon.ServerIntrospector;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;

import static org.springframework.cloud.netflix.ribbon.RibbonUtils.updateToSecureConnectionIfNeeded;

/**
 * Feign ribbon负载均衡器
 * @author Dave Syer
 * @author Spencer Gibb
 * @author Ryan Baxter
 * @author Tim Ysewyn
 * @author Olga Maciaszek-Sharma
 */
public class FeignLoadBalancer extends AbstractLoadBalancerAwareClient<FeignLoadBalancer.RibbonRequest, FeignLoadBalancer.RibbonResponse> {

	private final RibbonProperties ribbon;
	protected int connectTimeout;
	protected int readTimeout;
	// 配置
	protected IClientConfig clientConfig;
	protected ServerIntrospector serverIntrospector;

	public FeignLoadBalancer(ILoadBalancer lb, IClientConfig clientConfig,
			ServerIntrospector serverIntrospector) {
		super(lb, clientConfig);
		this.setRetryHandler(RetryHandler.DEFAULT);
		this.clientConfig = clientConfig;
		this.ribbon = RibbonProperties.from(clientConfig);
		RibbonProperties ribbon = this.ribbon;
		this.connectTimeout = ribbon.getConnectTimeout();
		this.readTimeout = ribbon.getReadTimeout();
		this.serverIntrospector = serverIntrospector;
	}

	/**
	 * 实现IClient方法，发送请求
	 */
	@Override
	public RibbonResponse execute(RibbonRequest request, IClientConfig configOverride) throws IOException {
		Request.Options options;
		if (configOverride != null) {
			RibbonProperties override = RibbonProperties.from(configOverride);
			int connectTimeout = override.connectTimeout(this.connectTimeout);
			int readTimeout = override.readTimeout(this.readTimeout);
			options = new Request.Options(connectTimeout,readTimeout);
		} else {
			options = new Request.Options(this.connectTimeout, this.readTimeout);
		}
		// 发送http请求(Client是委托的客户端)
		Response response = request.client().execute(request.toRequest(), options);
		return new RibbonResponse(request.getUri(), response);
	}

	/**
	 * 构造RequestSpecificRetryHandler，重试策略
	 */
	@Override
	public RequestSpecificRetryHandler getRequestSpecificRetryHandler(
			RibbonRequest request, IClientConfig requestConfig) {
		// 所有错误都重试
		if (this.ribbon.isOkToRetryOnAllOperations()) {
			return new RequestSpecificRetryHandler(true, true, this.getRetryHandler(),
					requestConfig);
		}
		// 非GET请求，连接错误时重试
		if (!request.toRequest().httpMethod().name().equals("GET")) {
			return new RequestSpecificRetryHandler(true, false, this.getRetryHandler(),
					requestConfig);
		} else {// GET请求，出错都重试
			return new RequestSpecificRetryHandler(true, true, this.getRetryHandler(),
					requestConfig);
		}
	}

	@Override
	public URI reconstructURIWithServer(Server server, URI original) {
		URI uri = updateToSecureConnectionIfNeeded(original, this.clientConfig,
				this.serverIntrospector, server);
		return super.reconstructURIWithServer(server, uri);
	}

	protected static class RibbonRequest extends ClientRequest implements Cloneable {
		// feign请求
		private final Request request;
		// feign底层客户端: 真正发送http的客户端，eg: httpClient OkClient
		private final Client client;

		protected RibbonRequest(Client client, Request request, URI uri) {
			this.client = client;
			// 不带域名的请求uri
			setUri(uri);
			this.request = toRequest(request);
		}

		/**
		 * 重置uri
		 */
		private Request toRequest(Request request) {
			Map<String, Collection<String>> headers = new LinkedHashMap<>(request.headers());
			return Request.create(request.httpMethod(), getUri().toASCIIString(), headers,
					request.body(), request.charset(), request.requestTemplate());
		}

		Request toRequest() {
			return toRequest(this.request);
		}

		Client client() {
			return this.client;
		}

		HttpRequest toHttpRequest() {
			return new HttpRequest() {
				@Override
				public HttpMethod getMethod() {
					return HttpMethod
							.resolve(RibbonRequest.this.toRequest().httpMethod().name());
				}

				@Override
				public String getMethodValue() {
					return getMethod().name();
				}

				@Override
				public URI getURI() {
					return RibbonRequest.this.getUri();
				}

				@Override
				public HttpHeaders getHeaders() {
					Map<String, List<String>> headers = new HashMap<>();
					Map<String, Collection<String>> feignHeaders = RibbonRequest.this
							.toRequest().headers();
					for (String key : feignHeaders.keySet()) {
						headers.put(key, new ArrayList<String>(feignHeaders.get(key)));
					}
					HttpHeaders httpHeaders = new HttpHeaders();
					httpHeaders.putAll(headers);
					return httpHeaders;

				}
			};
		}

		public Request getRequest() {
			return this.request;
		}

		public Client getClient() {
			return this.client;
		}

		@Override
		public Object clone() {
			return new RibbonRequest(this.client, this.request, getUri());
		}
	}

	protected static class RibbonResponse implements IResponse {

		private final URI uri;
		private final Response response;

		protected RibbonResponse(URI uri, Response response) {
			this.uri = uri;
			this.response = response;
		}

		@Override
		public Object getPayload() throws ClientException {
			return this.response.body();
		}

		@Override
		public boolean hasPayload() {
			return this.response.body() != null;
		}

		@Override
		public boolean isSuccess() {
			return this.response.status() == 200;
		}

		@Override
		public URI getRequestedURI() {
			return this.uri;
		}

		@Override
		public Map<String, Collection<String>> getHeaders() {
			return this.response.headers();
		}

		Response toResponse() {
			return this.response;
		}

		@Override
		public void close() throws IOException {
			if (this.response != null && this.response.body() != null) {
				this.response.body().close();
			}
		}
	}
}
