/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
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

/*
 One subproject per maddi module, each pointing at that module's real source directory. The modules are
 NOT merged: every maddi module is a JPMS module, and several module-info.java in one compilation collide
 ("too many module declarations"); dropping them instead makes javac compile the merge as one of the
 modules, and every `requires`d package then comes back as "package org.slf4j is not visible".

 cst-api and cst-impl are both analyzed as source because that is the whole point: TypeInfo is an
 interface in cst-api, TypeInfoImpl implements it in cst-impl, and eventual immutability can only travel
 between them when both are parsed -- a jar type never enters the abstract-method batch. The plugin's
 maddiSourceElements variant is what carries cst-api's sources into cst-impl's input configuration.
*/
plugins {
    // Resolved once here, applied in the subprojects. Deliberately NO version literal: settings.gradle.kts
    // supplies the project's own, read from the real build's gradle.properties, exactly as it does for the
    // maddi-support/maddi-util jar pins. Nothing under dogfood/ names a version, so nothing under dogfood/
    // can be left behind by a release.
    id("io.codelaser.maddi.analyzer") apply false
}
