//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.frames;

import org.eclipse.jetty.http.MetaData;

public class HeadersFrame extends StreamFrame
{
    private final MetaData metaData;
    private final PriorityFrame priority;
    private final boolean endStream;

    /**
     * <p>Creates a new {@code HEADERS} frame with an unspecified stream {@code id}.</p>
     * <p>The stream {@code id} will be generated by the implementation while sending
     * this frame to the other peer.</p>
     *
     * @param metaData the metadata containing HTTP request information
     * @param priority the PRIORITY frame associated with this HEADERS frame
     * @param endStream whether this frame ends the stream
     */
    public HeadersFrame(MetaData metaData, PriorityFrame priority, boolean endStream)
    {
        this(0, metaData, priority, endStream);
    }

    /**
     * <p>Creates a new {@code HEADERS} frame with the specified stream {@code id}.</p>
     * <p>{@code HEADERS} frames with a specific stream {@code id} are typically used
     * in responses to request {@code HEADERS} frames.</p>
     *
     * @param streamId the stream id
     * @param metaData the metadata containing HTTP request/response information
     * @param priority the PRIORITY frame associated with this HEADERS frame
     * @param endStream whether this frame ends the stream
     */
    public HeadersFrame(int streamId, MetaData metaData, PriorityFrame priority, boolean endStream)
    {
        super(FrameType.HEADERS, streamId);
        this.metaData = metaData;
        this.priority = priority;
        this.endStream = endStream;
    }

    public MetaData getMetaData()
    {
        return metaData;
    }

    public PriorityFrame getPriority()
    {
        return priority;
    }

    public boolean isEndStream()
    {
        return endStream;
    }

    @Override
    public HeadersFrame withStreamId(int streamId)
    {
        PriorityFrame priority = getPriority();
        priority = priority == null ? null : priority.withStreamId(streamId);
        return new HeadersFrame(streamId, getMetaData(), priority, isEndStream());
    }

    @Override
    public String toString()
    {
        return String.format("%s#%d[end=%b,{%s},priority=%s]", super.toString(), getStreamId(), isEndStream(), getMetaData(), getPriority());
    }
}
