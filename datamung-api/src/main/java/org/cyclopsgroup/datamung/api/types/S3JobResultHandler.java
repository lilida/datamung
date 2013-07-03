package org.cyclopsgroup.datamung.api.types;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

@XmlType
public class S3JobResultHandler
    extends JobResultHandler
{
    private String bucketName;

    private String objectKey;

    public S3JobResultHandler()
    {
        super( Type.S3 );
    }

    @XmlElement
    public String getBucketName()
    {
        return bucketName;
    }

    @XmlElement
    public String getObjectKey()
    {
        return objectKey;
    }

    public void setBucketName( String bucketName )
    {
        this.bucketName = bucketName;
    }

    public void setObjectKey( String objectKey )
    {
        this.objectKey = objectKey;
    }
}