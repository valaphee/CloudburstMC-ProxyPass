/*
 * Copyright (c) 2022, Valaphee.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.valaphee.service.minecraftservices.persona

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

suspend fun HttpClient.retrieveAppearance(uri: String = "https://persona.franchise.minecraft-services.net", authorization: String) = get("$uri/api/v1.0/appearance/retrieve?profilenames=personaProfile1&profilenames=personaProfile2&profilenames=personaProfile3&profilenames=personaProfile4&profilenames=personaProfile5&profilenames=personaProfile") { header(HttpHeaders.Authorization, authorization) }.body<String>()