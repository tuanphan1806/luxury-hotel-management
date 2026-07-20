package com.hotel.backend.dto.response;

import java.io.Serializable;
import lombok.Getter;
import java.util.List;
import lombok.Setter;
@Getter
@Setter
public class RoomPageResponse extends PageResponseAbstract implements Serializable{
    private List<RoomResponse> rooms;
}
