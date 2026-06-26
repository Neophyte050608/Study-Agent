package io.github.imzmq.interview.media.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.imzmq.interview.media.infrastructure.persistence.ImageMetadataDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ImageMetadataMapper extends BaseMapper<ImageMetadataDO> {
}


