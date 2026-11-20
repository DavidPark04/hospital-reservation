export type HospitalCoordinates = {latitude:number;longitude:number};
export type AddressResult = {
  x:string;y:string;address_type?:string;
  address?:{main_address_no?:string}|null;
  road_address?:{main_building_no?:string}|null;
};

// HIRA serves Korean hospitals. Reject missing, swapped and sentinel coordinates.
export function hospitalCoordinates(latitude:unknown,longitude:unknown):HospitalCoordinates|null {
  if(typeof latitude!=='number'||typeof longitude!=='number'||!Number.isFinite(latitude)||!Number.isFinite(longitude))return null;
  if(latitude<32||latitude>39.5||longitude<124||longitude>132)return null;
  return {latitude,longitude};
}

export function exactAddressCoordinates(results:AddressResult[],totalCount:number):HospitalCoordinates|null {
  if(totalCount!==1||results.length!==1)return null;
  const result=results[0];
  if(result.address_type!=='ROAD_ADDR'&&result.address_type!=='REGION_ADDR')return null;
  if(!result.road_address?.main_building_no&&!result.address?.main_address_no)return null;
  return hospitalCoordinates(Number(result.y),Number(result.x));
}

export function kakaoLocationLinks(name:string,address:string,coordinates:HospitalCoordinates|null){
  const search=`https://map.kakao.com/link/search/${encodeURIComponent([name,address].filter(Boolean).join(' '))}`;
  if(!coordinates)return {search,map:null,directions:null};
  const target=`${encodeURIComponent(name)},${coordinates.latitude},${coordinates.longitude}`;
  return {search,map:`https://map.kakao.com/link/map/${target}`,directions:`https://map.kakao.com/link/to/${target}`};
}
