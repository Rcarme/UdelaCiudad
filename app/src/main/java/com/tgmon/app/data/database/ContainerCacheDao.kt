package com.tgmon.app.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tgmon.app.data.models.ContainerCache
import kotlinx.coroutines.flow.Flow

@Dao
interface ContainerCacheDao {
    
    @Query("SELECT * FROM container_cache ORDER BY name ASC")
    fun getAllContainers(): Flow<List<ContainerCache>>
    
    @Query("SELECT * FROM container_cache ORDER BY name ASC")
    suspend fun getAllContainersList(): List<ContainerCache>
    
    @Query("SELECT * FROM container_cache WHERE id = :id LIMIT 1")
    suspend fun getContainer(id: String): ContainerCache?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContainer(container: ContainerCache)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    @Transaction
    suspend fun insertAll(containers: List<ContainerCache>)
    
    @Query("DELETE FROM container_cache WHERE id = :id")
    suspend fun deleteContainer(id: String)
    
    @Query("DELETE FROM container_cache")
    suspend fun clearAll()
    
    @Query("SELECT COUNT(*) FROM container_cache")
    suspend fun getCount(): Int
    
    @Query("DELETE FROM container_cache WHERE timestamp < :threshold")
    suspend fun deleteOlderThan(threshold: Long)
}