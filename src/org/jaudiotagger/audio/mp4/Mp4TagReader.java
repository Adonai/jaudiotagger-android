/*
 * Entagged Audio Tag library
 * Copyright (c) 2003-2005 Raphaël Slinckx <raphael@slinckx.net>
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *  
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.jaudiotagger.audio.mp4;

import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.generic.Utils;
import org.jaudiotagger.audio.mp4.atom.Mp4BoxHeader;
import org.jaudiotagger.audio.mp4.atom.Mp4MetaBox;
import org.jaudiotagger.logging.ErrorMessage;
import org.jaudiotagger.tag.FieldDataInvalidException;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.mp4.Mp4FieldKey;
import org.jaudiotagger.tag.mp4.Mp4NonStandardFieldKey;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.mp4.atom.Mp4DataBox;
import org.jaudiotagger.tag.mp4.field.*;
import org.jcodec.containers.mp4.MP4Util;
import org.jcodec.containers.mp4.boxes.*;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.*;

/**
 * Reads metadata from mp4,
 *
 * <p>The metadata tags are usually held under the ilst atom as shown below
 * <p>Valid Exceptions to the rule:
 * <p>Can be no udta atom with meta rooted immediately under moov instead
 * <p>Can be no udta/meta atom at all
 *
 * <pre>
 * |--- ftyp
 * |--- moov
 * |......|
 * |......|----- mvdh
 * |......|----- trak
 * |......|----- udta
 * |..............|
 * |..............|-- meta
 * |....................|
 * |....................|-- hdlr
 * |....................|-- ilst
 * |.........................|
 * |.........................|---- @nam (Optional for each metadatafield)
 * |.........................|.......|-- data
 * |.........................|....... ecetera
 * |.........................|---- ---- (Optional for reverse dns field)
 * |.................................|-- mean
 * |.................................|-- name
 * |.................................|-- data
 * |.................................... ecetere
 * |
 * |--- mdat
 * </pre
 */
public class Mp4TagReader
{

    // Logger Object
    public static Logger logger = Logger.getLogger("org.jaudiotagger.tag.mp4");

    /*
     * The metadata is stored in the box under the hierachy moov.udta.meta.ilst
     *
     * There are gaps between these boxes

     */
    public Mp4Tag read(RandomAccessFile raf) throws CannotReadException, IOException
    {
        MP4Util.Movie mp4 = MP4Util.parseFullMovieChannel(raf.getChannel());
        Mp4Tag tag = new Mp4Tag();

        //Get to the facts everything we are interested in is within the moov box, so just load data from file
        //once so no more file I/O needed

        if (mp4 == null || mp4.getMoov() == null)
        {
            throw new CannotReadException(ErrorMessage.MP4_FILE_NOT_CONTAINER.getMsg());
        }
        MovieBox moov = mp4.getMoov();

        //Level 2-Searching for "udta" within "moov"
        UdtaBox udta = NodeBox.findFirst(moov, UdtaBox.class, "udta");
        MetaBox meta;
        IListBox ilst;
        if (udta != null)
        {
            //Level 3-Searching for "meta" within udta
            meta = udta.meta();
            if (meta == null)
            {
                logger.warning(ErrorMessage.MP4_FILE_HAS_NO_METADATA.getMsg());
                return tag;
            }

            //Level 4- Search for "ilst" within meta
            ilst = NodeBox.findFirst(meta, IListBox.class, "ilst");
             //This file does not actually contain a tag
            if (ilst == null)
            {
                logger.warning(ErrorMessage.MP4_FILE_HAS_NO_METADATA.getMsg());
                return tag;
            }
        }
        else
        {
            //Level 2-Searching for "meta" not within udta
            meta = NodeBox.findFirst(moov, MetaBox.class, "meta");
            if (meta == null)
            {
                logger.warning(ErrorMessage.MP4_FILE_HAS_NO_METADATA.getMsg());
                return tag;
            }

            //Level 3- Search for "ilst" within meta
            ilst = NodeBox.findFirst(meta, IListBox.class, "ilst");
            //This file does not actually contain a tag
            if (ilst == null)
            {
                logger.warning(ErrorMessage.MP4_FILE_HAS_NO_METADATA.getMsg());
                return tag;
            }
        }

        createMp4Field(tag, meta, ilst);
        return tag;
    }

    /**
     * Process the field and add to the tag
     *
     * Note:In the case of coverart MP4 holds all the coverart within individual dataitems all within
     * a single covr atom, we will add separate mp4field for each image.
     *
     * @param header
     * @param raw
     * @param tag
     * @param meta
     * @return
     * @throws UnsupportedEncodingException
     */
    private void createMp4Field(Mp4Tag tag, MetaBox meta, IListBox ilst) throws UnsupportedEncodingException
    {
        //Header with no data #JAUDIOTAGGER-463
        if(ilst.getValues().isEmpty()) {
            return;
        }

        //Reverse Dns Atom
        Map<Integer, MetaValue> rawMeta = meta.getItunesMeta();
        Map<String, MetaValue> rdnsMeta = meta.getRdnsMeta();
        for (Mp4FieldKey key : Mp4FieldKey.values()) {
            byte[] nameBytes = key.getFieldName().getBytes(US_ASCII);
            Integer nameCoded = ByteBuffer.wrap(nameBytes).getInt();

            if (rdnsMeta.containsKey(key.getFieldName())) {
                MetaValue rdns = rdnsMeta.get(key.getFieldName());
                Mp4TagReverseDnsField field = new Mp4TagReverseDnsField(key.getFieldName(), key.getIssuer(), key.getIdentifier(), rdns.toString());
                tag.addField(field);
                continue;
            }

            if (rawMeta.containsKey(nameCoded)) {
                MetaValue metaValue = rawMeta.get(nameCoded);
                try {
                    switch (key) {
                        case TRACK:
                            tag.addField(new Mp4TrackField(metaValue.toString()));
                            break;
                        case DISCNUMBER:
                            tag.addField(new Mp4DiscNoField(metaValue.getString()));
                            break;
                        case GENRE:
                            tag.addField(new Mp4GenreField(metaValue.getString()));
                            break;
                        case ARTWORK:
                            tag.addField(new Mp4TagCoverField(metaValue.getData()));
                        default:
                            switch (key.getSubClassFieldType()) {
                                case TEXT:
                                    tag.addField(new Mp4TagTextField(key.getFieldName(), metaValue.toString()));
                            }

                            break;
                    }
                } catch (FieldDataInvalidException e) {
                    logger.warning(ErrorMessage.MP4_UNABLE_READ_REVERSE_DNS_FIELD.getMsg(e.getMessage()));
                    TagField field = new Mp4TagRawBinaryField(new Mp4BoxHeader(new String(nameBytes, US_ASCII)), ByteBuffer.wrap(metaValue.getData()));
                    tag.addField(field);
                }
            }
        }
    }
}
