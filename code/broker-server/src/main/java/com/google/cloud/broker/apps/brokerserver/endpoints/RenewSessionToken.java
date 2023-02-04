// Copyright 2020 Google LLC
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.cloud.broker.apps.brokerserver.endpoints;

import com.google.cloud.broker.apps.brokerserver.logging.LoggingUtils;
import com.google.cloud.broker.apps.brokerserver.protobuf.RenewSessionTokenRequest;
import com.google.cloud.broker.apps.brokerserver.protobuf.RenewSessionTokenResponse;
import com.google.cloud.broker.apps.brokerserver.sessions.Session;
import com.google.cloud.broker.apps.brokerserver.sessions.SessionTokenUtils;
import com.google.cloud.broker.apps.brokerserver.validation.GrpcRequestValidation;
import com.google.cloud.broker.authentication.backends.AbstractAuthenticationBackend;
import com.google.cloud.broker.database.backends.AbstractDatabaseBackend;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.MDC;

public class RenewSessionToken {

  public static void run(
      RenewSessionTokenRequest request,
      StreamObserver<RenewSessionTokenResponse> responseObserver) {
    MDC.put(LoggingUtils.MDC_METHOD_NAME_KEY, RenewSessionToken.class.getSimpleName());

    AbstractAuthenticationBackend authenticator = AbstractAuthenticationBackend.getInstance();
    String authenticatedUser = authenticator.authenticateUser();

    GrpcRequestValidation.validateParameterNotEmpty("session_token", request.getSessionToken());

    // Retrieve the session details from the database
    Session session = SessionTokenUtils.getSessionFromRawToken(request.getSessionToken());

    // Verify that the caller is the authorized renewer for the toke
    if (!session.getRenewer().equals(authenticatedUser)) {
      throw Status.PERMISSION_DENIED
          .withDescription(String.format("Unauthorized renewer: %s", authenticatedUser))
          .asRuntimeException();
    }

    // Extend session's lifetime
    session.extendLifetime();
    AbstractDatabaseBackend.getInstance().save(session);

    // Log success message
    MDC.put(LoggingUtils.MDC_AUTH_MODE_KEY, LoggingUtils.MDC_AUTH_MODE_VALUE_DIRECT);
    MDC.put(LoggingUtils.MDC_OWNER_KEY, session.getOwner());
    MDC.put(LoggingUtils.MDC_RENEWER_KEY, session.getRenewer());
    MDC.put(LoggingUtils.MDC_SESSION_ID_KEY, session.getId());
    LoggingUtils.successAuditLog();

    // Return response
    RenewSessionTokenResponse response =
        RenewSessionTokenResponse.newBuilder().setExpiresAt(session.getExpiresAt()).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }
}
