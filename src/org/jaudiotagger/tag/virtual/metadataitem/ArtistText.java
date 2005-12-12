/**
 * Initial @author : Paul Taylor
 * <p/>
 * Version @version:$Id$
 * Date :${DATE}
 * <p/>
 * Jaikoz Copyright Copyright (C) 2003 -2005 JThink Ltd
 */
package org.jaudiotagger.tag.virtual.metadataitem;

import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.id3.framebody.AbstractID3v2FrameBody;
import org.jaudiotagger.tag.id3.framebody.AbstractFrameBodyTextInfo;

/**
 * Represents the Primary Artist on the recording
 */
public class ArtistText  extends AbstractText
{
    public ArtistText(ID3v24Frame id3v24Frame)
    {
        super(id3v24Frame);
    }
}