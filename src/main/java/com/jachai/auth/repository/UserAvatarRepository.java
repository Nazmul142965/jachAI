package com.jachai.auth.repository;

import com.jachai.auth.model.UserAvatar;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserAvatarRepository extends MongoRepository<UserAvatar, String> { }
