package org.jaudiotagger.tag.id3;

import org.jaudiotagger.tag.TagTextField;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentFieldKey;

import java.io.UnsupportedEncodingException;

/**
 * This class encapsulates the name and content of a tag entry in id3 fields
 * <br>
 *
 * @author @author Raphael Slinckx (KiKiDonK)
 * @author Christian Laireiter (liree)
 */
public class ID3TagField implements TagTextField
{

    /**
     * If <code>true</code>, the id of the current encapsulated tag field is
     * specified as a common field. <br>
     * Example is "ARTIST" which should be interpreted by any application as the
     * artist of the media content. <br>
     * Will be set during construction with {@link #checkCommon()}.
     */
    private boolean common;

    /**
     * Stores the content of the tag field. <br>
     */
    private String content;

    /**
     * Stores the id (name) of the tag field. <br>
     */
    private String id;

    /**
     * Creates an instance.
     *
     * @param raw Raw byte data of the tagfield.
     * @throws UnsupportedEncodingException If the data doesn't conform "UTF-8" specification.
     */
    public ID3TagField(byte[] raw) throws UnsupportedEncodingException
    {
        String field = new String(raw, "UTF-8");

        int i = field.indexOf("=");
        if (i == -1)
        {
            //Beware that ogg ID, must be capitalized and contain no space..
            this.id = "ERRONEOUS";
            this.content = field;
        }
        else
        {
            this.id = field.substring(0, i).toUpperCase();
            if (field.length() > i)
            {
                this.content = field.substring(i + 1);
            }
            else
            {
                //We have "XXXXXX=" with nothing after the "="
                this.content = "";
            }
        }
        checkCommon();
    }

    /**
     * Creates an instance.
     *
     * @param fieldId      ID (name) of the field.
     * @param fieldContent Content of the field.
     */
    public ID3TagField(String fieldId, String fieldContent)
    {
        this.id = fieldId.toUpperCase();
        this.content = fieldContent;
        checkCommon();
    }

    /**
     * This method examines the ID of the current field and modifies
     * {@link #common}in order to reflect if the tag id is a commonly used one.
     * <br>
     */
    private void checkCommon()
    {
        this.common = id.equals(ID3FieldKey.TITLE.name())
            || id.equals(ID3FieldKey.ALBUM.name())
            || id.equals(ID3FieldKey.ARTIST.name())
            || id.equals(ID3FieldKey.GENRE.name())
            || id.equals(ID3FieldKey.TRACKNUMBER.name())
            || id.equals(ID3FieldKey.YEAR.name())
            || id.equals(ID3FieldKey.DESCRIPTION.name())
            || id.equals(ID3FieldKey.COMMENT.name())
            || id.equals(ID3FieldKey.TRACK.name());
    }

    /**
     * This method will copy all bytes of <code>src</code> to <code>dst</code>
     * at the specified location.
     *
     * @param src       bytes to copy.
     * @param dst       where to copy to.
     * @param dstOffset at which position of <code>dst</code> the data should be
     *                  copied.
     */
    protected void copy(byte[] src, byte[] dst, int dstOffset)
    {
        //        for (int i = 0; i < src.length; i++)
        //            dst[i + dstOffset] = src[i];
        /*
         * Heared that this method is optimized and does its job very near of
         * the system.
         */
        System.arraycopy(src, 0, dst, dstOffset, src.length);
    }

    /**
     * @see TagField#copyContent(TagField)
     */
    public void copyContent(TagField field)
    {
        if (field instanceof TagTextField)
        {
            this.content = ((TagTextField) field).getContent();
        }
    }

    /**
     * This method will try to return the byte representation of the given
     * string after it has been converted to the given encoding. <br>
     *
     * @param s        The string whose converted bytes should be returned.
     * @param encoding The encoding type to which the string should be converted.
     * @return If <code>encoding</code> is supported the byte data of the
     *         given string is returned in that encoding.
     * @throws UnsupportedEncodingException If the requested encoding is not available.
     */
    protected byte[] getBytes(String s, String encoding) throws UnsupportedEncodingException
    {
        return s.getBytes(encoding);
    }

    /**
     * @see TagTextField#getContent()
     */
    public String getContent()
    {
        return content;
    }

    /**
     * @see TagTextField#getEncoding()
     */
    public String getEncoding()
    {
        return "ISO-8859-1";
    }

    /**
     * @see TagField#getId()
     */
    public String getId()
    {
        return this.id;
    }

    /**
     * @see TagField#getRawContent()
     */
    public byte[] getRawContent() throws UnsupportedEncodingException
    {
        byte[] size = new byte[4];
        byte[] idBytes = this.id.getBytes();
        byte[] contentBytes = getBytes(this.content, "UTF-8");
        byte[] b = new byte[4 + idBytes.length + 1 + contentBytes.length];

        int length = idBytes.length + 1 + contentBytes.length;
        size[3] = (byte) ((length & 0xFF000000) >> 24);
        size[2] = (byte) ((length & 0x00FF0000) >> 16);
        size[1] = (byte) ((length & 0x0000FF00) >> 8);
        size[0] = (byte) (length & 0x000000FF);

        int offset = 0;
        copy(size, b, offset);
        offset += 4;
        copy(idBytes, b, offset);
        offset += idBytes.length;
        b[offset] = (byte) 0x3D;
        offset++;// "="
        copy(contentBytes, b, offset);

        return b;
    }

    /**
     * @see TagField#isBinary()
     */
    public boolean isBinary()
    {
        return false;
    }

    /**
     * @see TagField#isBinary(boolean)
     */
    public void isBinary(boolean b)
    {
        if (b)
        {
            // Only throw if binary = true requested.
            throw new UnsupportedOperationException("OggTagFields cannot be changed to binary.\n"
                + "binary data should be stored elsewhere" + " according to Vorbis_I_spec.");
        }
    }

    /**
     * @see TagField#isCommon()
     */
    public boolean isCommon()
    {
        return common;
    }

    /**
     * @see TagField#isEmpty()
     */
    public boolean isEmpty()
    {
        return this.content.equals("");
    }

    /**
     * @see TagTextField#setContent(String)
     */
    public void setContent(String s)
    {
        this.content = s;
    }

    /**
     * @see TagTextField#setEncoding(String)
     */
    public void setEncoding(String s)
    {
        if (s == null || !s.equalsIgnoreCase("UTF-8"))
        {
            throw new UnsupportedOperationException("The encoding of OggTagFields cannot be " + "changed.(specified to be UTF-8)");
        }
    }

    public String toString()
    {
        return getContent();
    }
}