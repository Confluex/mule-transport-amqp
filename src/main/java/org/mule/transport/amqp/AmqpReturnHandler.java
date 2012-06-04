/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.amqp;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.Validate;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mule.DefaultMuleEvent;
import org.mule.MessageExchangePattern;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.construct.FlowConstruct;
import org.mule.api.processor.MessageProcessor;
import org.mule.api.transport.PropertyScope;
import org.mule.processor.AbstractInterceptingMessageProcessor;
import org.mule.transport.amqp.AmqpConnector.AmqpConnectorFlowConstruct;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ReturnListener;

/**
 * Message processor that sets the return listener for the flow, leaving it up to the
 * dispatcher to set it on the channel.<br/>
 * This class is also the holder of all the different return listeners of the
 * transport.
 */
public class AmqpReturnHandler extends AbstractInterceptingMessageProcessor
{
    public static abstract class AbstractAmqpReturnHandlerListener implements ReturnListener
    {
        protected static Log LOGGER = LogFactory.getLog(AmqpReturnHandler.class);

        public void handleReturn(final int replyCode,
                                 final String replyText,
                                 final String exchange,
                                 final String routingKey,
                                 final AMQP.BasicProperties properties,
                                 final byte[] body) throws IOException
        {
            final String errorMessage = String.format(
                "AMQP returned message with code: %d, reason: %s, exchange: %s, routing key: %s", replyCode,
                replyText, exchange, routingKey);

            final Map<String, Object> returnContext = new HashMap<String, Object>(4);
            returnContext.put(AmqpConstants.RETURN_REPLY_CODE, replyCode);
            returnContext.put(AmqpConstants.RETURN_REPLY_TEXT, replyText);
            returnContext.put(AmqpConstants.RETURN_EXCHANGE, exchange);
            returnContext.put(AmqpConstants.RETURN_ROUTING_KEY, routingKey);

            final AmqpMessage returnedAmqpMessage = new AmqpMessage(null, null, properties, body);

            doHandleReturn(errorMessage, returnContext, returnedAmqpMessage);
        }

        protected abstract void doHandleReturn(String errorMessage,
                                               Map<String, Object> returnContext,
                                               AmqpMessage returnedAmqpMessage);

        @Override
        public String toString()
        {
            return ToStringBuilder.reflectionToString(this);
        }
    }

    public static class LoggingReturnListener extends AbstractAmqpReturnHandlerListener
    {
        protected final AtomicInteger hitCount = new AtomicInteger(0);

        @Override
        protected void doHandleReturn(final String errorMessage,
                                      final Map<String, Object> ignored,
                                      final AmqpMessage returnedAmqpMessage)
        {
            hitCount.incrementAndGet();
            LOGGER.warn(String.format("%s: %s", errorMessage, returnedAmqpMessage));
        }

        public int getHitCount()
        {
            return hitCount.intValue();
        }
    }

    public static class DispatchingReturnListener extends AbstractAmqpReturnHandlerListener
    {
        protected final FlowConstruct eventFlowConstruct;
        protected final List<MessageProcessor> returnMessageProcessors;

        protected volatile AmqpConnector amqpConnector;

        public DispatchingReturnListener(final List<MessageProcessor> returnMessageProcessors,
                                         final MuleEvent event)
        {
            this(event.getFlowConstruct(), returnMessageProcessors);
        }

        public DispatchingReturnListener(final List<MessageProcessor> returnMessageProcessors,
                                         final AmqpConnectorFlowConstruct flowConstruct)
        {
            this(flowConstruct, returnMessageProcessors);
            this.amqpConnector = flowConstruct.getConnector();
        }

        private DispatchingReturnListener(final FlowConstruct eventFlowConstruct,
                                          final List<MessageProcessor> returnMessageProcessors)
        {
            Validate.notNull(eventFlowConstruct, "eventFlowConstruct can't be null");
            this.eventFlowConstruct = eventFlowConstruct;
            this.returnMessageProcessors = returnMessageProcessors;
        }

        public void setAmqpConnector(final AmqpConnector amqpConnector)
        {
            this.amqpConnector = amqpConnector;
        }

        @Override
        protected void doHandleReturn(final String errorMessage,
                                      final Map<String, Object> returnContext,
                                      final AmqpMessage returnedAmqpMessage)
        {
            try
            {
                // thread safe copy of the message
                final MuleMessage returnedMuleMessage = amqpConnector.getMuleMessageFactory().create(
                    returnedAmqpMessage,
                    amqpConnector.getMuleContext().getConfiguration().getDefaultEncoding());

                returnedMuleMessage.addProperties(returnContext, PropertyScope.INBOUND);

                for (final MessageProcessor returnMessageProcessor : returnMessageProcessors)
                {
                    final DefaultMuleEvent returnedMuleEvent = new DefaultMuleEvent(returnedMuleMessage,
                        MessageExchangePattern.ONE_WAY, eventFlowConstruct);

                    returnedMuleMessage.applyTransformers(returnedMuleEvent,
                        amqpConnector.getReceiveTransformer());

                    returnMessageProcessor.process(returnedMuleEvent);
                }
            }
            catch (final Exception e)
            {
                LOGGER.error(String.format(
                    "%s, impossible to dispatch the following message to the configured endpoint(s): %s",
                    errorMessage, returnedAmqpMessage), e);
            }
        }
    }

    public static final ReturnListener DEFAULT_RETURN_LISTENER = new LoggingReturnListener();

    private List<MessageProcessor> returnMessageProcessors;

    public void setMessageProcessors(final List<MessageProcessor> returnMessageProcessors)
    {
        this.returnMessageProcessors = returnMessageProcessors;
    }

    public MuleEvent process(final MuleEvent event) throws MuleException
    {
        final DispatchingReturnListener returnListener = new DispatchingReturnListener(
            returnMessageProcessors, event);
        event.getMessage().setInvocationProperty(AmqpConstants.RETURN_LISTENER, returnListener);
        return processNext(event);
    }
}
