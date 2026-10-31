package com.smart.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smart.product.entity.Product;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
