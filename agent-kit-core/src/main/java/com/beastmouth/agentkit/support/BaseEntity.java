package com.beastmouth.agentkit.support;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体公共字段：主键、审计时间、逻辑删除、乐观锁版本。
 *
 * <p>脚手架自带的持久化实体（会话、消息、记忆、评测用例）都继承它，
 * 保证「谁建的、什么时候改的、软删除」这套语义一致。</p>
 *
 * <p><b>注意填充器的前提</b>：{@code createTime}/{@code updateTime} 依赖
 * MyBatis-Plus 的 {@code MetaObjectHandler} 自动填充。使用方如果没有注册填充器，
 * 这两列会是 null——要么加填充器，要么在插入前手动赋值。脚手架不强塞一个全局填充器，
 * 因为那会覆盖使用方已有的实现。</p>
 */
@Data
public abstract class BaseEntity implements Serializable {

    /** 主键，雪花算法生成 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除：0-正常 1-已删除，由 MyBatis-Plus 自动维护 */
    @TableLogic
    @TableField(select = false)
    private Integer deleted;

    /** 乐观锁版本号：插入时置 0，每次更新自增 */
    @Version
    @TableField(fill = FieldFill.INSERT)
    private Integer version;
}
