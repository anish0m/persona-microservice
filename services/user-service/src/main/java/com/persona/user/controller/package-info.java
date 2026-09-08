/**
 * <h2>The web edge of user-service.</h2>
 *
 * Thin controllers: read request, call one service method, map result to a status code.
 *
 * <p>One extra job compared to the monolith: this is now a <b>public contract</b>.
 * In the monolith a controller serves only the browser, so renaming a JSON field
 * breaks one frontend you control. Here, other services may also be calling it, so
 * the same rename can break production for a team that never reviewed your PR.
 *
 * <p>On Day-17 an API Gateway sits in front of these controllers and becomes the
 * single address the browser talks to.
 */
package com.persona.user.controller;
