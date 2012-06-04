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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.model.SessionException;
import org.mule.api.processor.MessageProcessor;
import org.mule.config.i18n.MessageFactory;

import com.rabbitmq.client.Channel;

/**
 * Used to manually perform a basic reject of the message in flow, allowing fine
 * control of message throttling. It looks for a delivery-tag inbound message
 * property and an amqp.channel session property. If the former is missing, it logs a
 * warning. If the former is present but not the latter, it throws an exception.
 */
public class AmqpMessageRejecter implements MessageProcessor
{
    private final static Log LOG = LogFactory.getLog(AmqpMessageRejecter.class);

    protected boolean requeue = false;

    public MuleEvent process(final MuleEvent event) throws MuleException
    {
        reject(event, requeue);
        return event;
    }

    public void setRequeue(final boolean requeue)
    {
        this.requeue = requeue;
    }

    public static void reject(final MuleEvent event, final boolean multiple) throws SessionException
    {
        reject(event.getMessage(), multiple);
    }

    public static void reject(final MuleMessage message, final boolean requeue) throws SessionException
    {
        final Long deliveryTag = message.getInboundProperty(AmqpConstants.DELIVERY_TAG);

        if (deliveryTag == null)
        {
            LOG.warn("Missing " + AmqpConstants.DELIVERY_TAG
                     + " inbound property, impossible to reject message: " + message);
            return;
        }

        final Channel channel = AmqpConnector.getChannelFromMessage(message);

        if (channel == null)
        {
            throw new SessionException(
                MessageFactory.createStaticMessage("No "
                                                   + AmqpConstants.CHANNEL
                                                   + " session property found, impossible to reject message: "
                                                   + message));
        }

        try
        {
            channel.basicReject(deliveryTag, requeue);
        }
        catch (final IOException ioe)
        {
            throw new SessionException(
                MessageFactory.createStaticMessage("Failed to reject message w/deliveryTag: " + deliveryTag
                                                   + " on channel: " + channel), ioe);
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Manually rejected message w/deliveryTag: " + deliveryTag + " on channel: " + channel);
        }
    }

}
